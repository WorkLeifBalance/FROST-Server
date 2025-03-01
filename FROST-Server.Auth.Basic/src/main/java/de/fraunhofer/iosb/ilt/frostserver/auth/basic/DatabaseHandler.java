/*
 * Copyright (C) 2023 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
 * Karlsruhe, Germany.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.frostserver.auth.basic;

import static de.fraunhofer.iosb.ilt.frostserver.auth.basic.BasicAuthProvider.LIQUIBASE_CHANGELOG_FILENAME;
import static de.fraunhofer.iosb.ilt.frostserver.auth.basic.BasicAuthProvider.TAG_AUTO_UPDATE_DATABASE;
import static de.fraunhofer.iosb.ilt.frostserver.auth.basic.BasicAuthProvider.TAG_PLAIN_TEXT_PASSWORD;
import static de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.utils.ConnectionUtils.TAG_DB_URL;

import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.utils.ConnectionUtils;
import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.utils.ConnectionUtils.ConnectionWrapper;
import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.utils.LiquibaseHelper;
import de.fraunhofer.iosb.ilt.frostserver.settings.CoreSettings;
import de.fraunhofer.iosb.ilt.frostserver.settings.Settings;
import de.fraunhofer.iosb.ilt.frostserver.util.LiquibaseUtils;
import de.fraunhofer.iosb.ilt.frostserver.util.exception.UpgradeFailedException;
import de.fraunhofer.iosb.ilt.frostserver.util.user.UserData;
import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author scf
 */
public class DatabaseHandler {

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseHandler.class);

    private static final Map<CoreSettings, DatabaseHandler> INSTANCES = new HashMap<>();

    private final CoreSettings coreSettings;
    private final Settings authSettings;
    private final boolean plainTextPassword;
    private final String connectionUrl;
    private boolean maybeUpdateDatabase;

    public static void init(CoreSettings coreSettings) {
        if (INSTANCES.get(coreSettings) == null) {
            createInstance(coreSettings);
        }
    }

    private static synchronized DatabaseHandler createInstance(CoreSettings coreSettings) {
        return INSTANCES.computeIfAbsent(coreSettings, s -> {
            LOGGER.info("Initialising DatabaseHandler.");
            return new DatabaseHandler(coreSettings);
        });
    }

    public static DatabaseHandler getInstance(CoreSettings coreSettings) {
        DatabaseHandler instance = INSTANCES.get(coreSettings);
        if (instance == null) {
            LOGGER.error("DatabaseHandler not initialised.");
        }
        return instance;
    }

    private DatabaseHandler(CoreSettings coreSettings) {
        this.coreSettings = coreSettings;
        authSettings = coreSettings.getAuthSettings();
        maybeUpdateDatabase = authSettings.getBoolean(TAG_AUTO_UPDATE_DATABASE, BasicAuthProvider.class);
        plainTextPassword = authSettings.getBoolean(TAG_PLAIN_TEXT_PASSWORD, BasicAuthProvider.class);
        connectionUrl = authSettings.get(TAG_DB_URL, ConnectionUtils.class, false);
    }

    public boolean isPlainTextPassword() {
        return plainTextPassword;
    }

    private Condition passwordCondition(String passwordOrHash) {
        return TableUsers.USERS.userPass.eq(plainTextPassword
                ? DSL.val(passwordOrHash)
                : DSL.function(
                        "crypt", String.class, DSL.val(passwordOrHash), TableUsers.USERS.userPass));
    }

    /**
     * Checks if the user is valid and adds roles to the userData.
     *
     * @param userData the user data (username and password)
     * @return true if the user is value
     */
    public boolean isValidUser(UserData userData) {
        maybeUpdateDatabase();
        try (final ConnectionWrapper connectionProvider = new ConnectionWrapper(authSettings, connectionUrl)) {
            final DSLContext dslContext = DSL.using(connectionProvider.get(), SQLDialect.POSTGRES);
            Result<Record1<String>> roles = dslContext
                    .select(TableUsersRoles.USER_ROLES.roleName)
                    .from(TableUsers.USERS)
                    .leftJoin(TableUsersRoles.USER_ROLES).on(TableUsers.USERS.userName.eq(TableUsersRoles.USER_ROLES.userName))
                    .where(
                            TableUsers.USERS.userName.eq(userData.userName)
                                    .and(passwordCondition(userData.userPass)))
                    .fetch();
            roles.getValues(TableUsersRoles.USER_ROLES.roleName)
                    .stream()
                    .filter(Objects::nonNull)
                    .forEach(userData.roles::add);
            return !roles.isEmpty();
        } catch (SQLException | RuntimeException exc) {
            LOGGER.error("Failed to check user credentials.", exc);
            return false;
        }
    }

    /**
     * This method checks if the given user exists with the given password and
     * has the given role.
     *
     * @param userName The username of the user to check the role for.
     * @param userPassOrHash The password or its hash of the user to check the
     * role for.
     * @param roleName The role to check.
     * @return true if the user exists AND has the given password AND has the
     * given role.
     */
    public boolean userHasRole(String userName, String userPassOrHash, String roleName) {
        maybeUpdateDatabase();
        try (final ConnectionWrapper connectionProvider = new ConnectionWrapper(authSettings, connectionUrl)) {
            final DSLContext dslContext = DSL.using(connectionProvider.get(), SQLDialect.POSTGRES);
            Record1<Integer> one = dslContext
                    .selectOne()
                    .from(TableUsers.USERS)
                    .leftJoin(TableUsersRoles.USER_ROLES)
                    .on(TableUsers.USERS.userName.eq(TableUsersRoles.USER_ROLES.userName))
                    .where(
                            TableUsers.USERS.userName.eq(userName)
                                    .and(passwordCondition(userPassOrHash))
                                    .and(TableUsersRoles.USER_ROLES.roleName.eq(roleName)))
                    .fetchOne();
            return one != null;
        } catch (SQLException | RuntimeException exc) {
            LOGGER.error("Failed to check user rights.", exc);
            return false;
        }
    }

    /**
     * This method checks if the given user exists and has the given role.
     *
     * @param userName The username of the user to check the role for.
     * @param roleName The role to check.
     * @return true if the user exists AND has the given password AND has the
     * given role.
     */
    public boolean userHasRole(String userName, String roleName) {
        if (userName == null) {
            return false;
        }
        maybeUpdateDatabase();
        try (final ConnectionWrapper connectionProvider = new ConnectionWrapper(authSettings, connectionUrl)) {
            final DSLContext dslContext = DSL.using(connectionProvider.get(), SQLDialect.POSTGRES);
            Record1<Integer> one = dslContext
                    .selectOne()
                    .from(TableUsersRoles.USER_ROLES)
                    .where(
                            TableUsersRoles.USER_ROLES.userName.eq(userName)
                                    .and(TableUsersRoles.USER_ROLES.roleName.eq(roleName)))
                    .fetchOne();
            return one != null;
        } catch (SQLException | RuntimeException exc) {
            LOGGER.error("Failed to check user rights.", exc);
            return false;
        }
    }

    private void maybeUpdateDatabase() {
        if (maybeUpdateDatabase) {
            BasicAuthProvider basicAuthProvider = new BasicAuthProvider();
            basicAuthProvider.init(coreSettings);
            maybeUpdateDatabase = LiquibaseUtils.maybeUpdateDatabase(LOGGER, basicAuthProvider);
        }
    }

    public String checkForUpgrades() {
        return checkForUpgrades(Collections.emptyMap());
    }

    public String checkForUpgrades(Map<String, Object> params) {
        Settings customSettings = coreSettings.getAuthSettings();
        try (Connection connection = ConnectionUtils.getConnection(connectionUrl, customSettings)) {
            return LiquibaseHelper.checkForUpgrades(connection, LIQUIBASE_CHANGELOG_FILENAME, params);
        } catch (SQLException ex) {
            LOGGER.error("Could not initialise database.", ex);
            return "Failed to initialise database:\n"
                    + ex.getLocalizedMessage()
                    + "\n";
        }
    }

    public boolean doUpgrades(Writer out) throws UpgradeFailedException, IOException {
        return doUpgrades(out, Collections.emptyMap());
    }

    public boolean doUpgrades(Writer out, Map<String, Object> params) throws UpgradeFailedException, IOException {
        Settings customSettings = coreSettings.getAuthSettings();
        try (Connection connection = ConnectionUtils.getConnection(connectionUrl, customSettings)) {
            return LiquibaseHelper.doUpgrades(connection, LIQUIBASE_CHANGELOG_FILENAME, params, out);
        } catch (SQLException ex) {
            LOGGER.error("Could not initialise database.", ex);
            out.append("Failed to initialise database:\n");
            out.append(ex.getLocalizedMessage());
            out.append("\n");
            return false;
        }
    }
}
