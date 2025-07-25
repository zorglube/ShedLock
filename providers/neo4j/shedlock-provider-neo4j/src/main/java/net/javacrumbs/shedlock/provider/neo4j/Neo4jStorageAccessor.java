/**
 * Copyright 2009 the original author or authors.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.javacrumbs.shedlock.provider.neo4j;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.support.AbstractStorageAccessor;
import net.javacrumbs.shedlock.support.LockException;
import net.javacrumbs.shedlock.support.annotation.Nullable;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;

class Neo4jStorageAccessor extends AbstractStorageAccessor {
    private final String collectionName;
    private final Driver driver;

    @Nullable
    private final String databaseName;

    public Neo4jStorageAccessor(Driver driver, String collectionName, @Nullable String databaseName) {
        this.collectionName = requireNonNull(collectionName, "collectionName can not be null");
        this.driver = requireNonNull(driver, "driver can not be null");
        this.databaseName = databaseName;
        createLockNameUniqueConstraint();
    }

    private void createLockNameUniqueConstraint() {
        try (Session session = getSession();
                Transaction transaction = session.beginTransaction()) {
            transaction.run(String.format(
                    "CREATE CONSTRAINT UNIQUE_%s_name IF NOT EXISTS FOR (lock:%s) REQUIRE lock.name IS UNIQUE",
                    collectionName, collectionName));
            transaction.commit();
        }
    }

    @Override
    public boolean insertRecord(LockConfiguration lockConfiguration) {
        // Try to insert if the record does not exists
        String cypher = String.format(
                "CYPHER runtime = slotted CREATE (lock:%s {name: $lockName, lock_until: $lockUntil, locked_at: $now, locked_by: $lockedBy })",
                collectionName);
        Map<String, Object> parameters = createParameterMap(lockConfiguration);
        return executeCommand(
                cypher,
                result -> {
                    int insertedNodes = result.consume().counters().nodesCreated();
                    return insertedNodes > 0;
                },
                parameters,
                this::handleInsertionException);
    }

    private Map<String, Object> createParameterMap(LockConfiguration lockConfiguration) {
        return Map.of(
                "lockName",
                lockConfiguration.getName(),
                "lockedBy",
                getHostname(),
                "now",
                ClockProvider.now().toString(),
                "lockUntil",
                lockConfiguration.getLockAtMostUntil().toString());
    }

    @Override
    public boolean updateRecord(LockConfiguration lockConfiguration) {
        String cypher = String.format(
                "CYPHER runtime = slotted MATCH (lock:%s) WHERE lock.name = $lockName AND lock.lock_until <= $now "
                        + "SET lock._LOCK_ = true WITH lock as l WHERE l.lock_until <= $now "
                        + "SET l.lock_until = $lockUntil, l.locked_at = $now, l.locked_by = $lockedBy "
                        + "REMOVE l._LOCK_ ",
                collectionName);
        Map<String, Object> parameters = createParameterMap(lockConfiguration);
        return executeCommand(
                cypher,
                statement -> {
                    int updatedProperties = statement.consume().counters().propertiesSet();
                    return updatedProperties > 1; // ignore explicit lock when counting the updated properties
                },
                parameters,
                this::handleUpdateException);
    }

    @Override
    public boolean extend(LockConfiguration lockConfiguration) {
        String cypher = String.format(
                "CYPHER runtime = slotted MATCH (lock:%s) "
                        + "WHERE lock.name = $lockName AND lock.locked_by = $lockedBy AND lock.lock_until > $now "
                        + "SET lock._LOCK_ = true WITH lock as l "
                        + "WHERE l.name = $lockName AND l.locked_by = $lockedBy AND l.lock_until > $now "
                        + "SET l.lock_until = $lockUntil REMOVE l._LOCK_ ",
                collectionName);
        Map<String, Object> parameters = createParameterMap(lockConfiguration);

        logger.debug("Extending lock={} until={}", lockConfiguration.getName(), lockConfiguration.getLockAtMostUntil());

        return executeCommand(
                cypher,
                statement -> {
                    int updatedProperties = statement.consume().counters().propertiesSet();
                    return updatedProperties > 1; // ignore the explicit lock when counting the updated properties
                },
                parameters,
                this::handleUnlockException);
    }

    @Override
    public void unlock(LockConfiguration lockConfiguration) {
        String cypher = String.format(
                "CYPHER runtime = slotted MATCH (lock:%s) WHERE lock.name = $lockName SET lock.lock_until = $lockUntil",
                collectionName);
        Map<String, Object> parameters = Map.of(
                "lockName",
                lockConfiguration.getName(),
                "lockUntil",
                lockConfiguration.getUnlockTime().toString());
        executeCommand(cypher, statement -> null, parameters, this::handleUnlockException);
    }

    private <T> T executeCommand(
            String cypher,
            Function<Result, T> body,
            Map<String, Object> parameters,
            BiFunction<String, Exception, T> exceptionHandler) {
        try (Session session = getSession();
                Transaction transaction = session.beginTransaction()) {
            Result result = transaction.run(cypher, parameters);
            T apply = body.apply(result);
            transaction.commit();
            return apply;
        } catch (Exception e) {
            return exceptionHandler.apply(cypher, e);
        }
    }

    private Session getSession() {
        return databaseName == null ? driver.session() : driver.session(SessionConfig.forDatabase(databaseName));
    }

    boolean handleInsertionException(String cypher, Exception e) {
        // lock record already exists
        return false;
    }

    boolean handleUpdateException(String cypher, Exception e) {
        throw new LockException("Unexpected exception when locking", e);
    }

    boolean handleUnlockException(String cypher, Exception e) {
        throw new LockException("Unexpected exception when unlocking", e);
    }
}
