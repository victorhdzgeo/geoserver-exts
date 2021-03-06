package org.geogig.geoserver.config;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static org.geogig.geoserver.config.LogStoreInitializer.copySampleInitSript;
import static org.geogig.geoserver.config.LogStoreInitializer.createDefaultConfig;
import static org.geogig.geoserver.config.LogStoreInitializer.newDataSource;
import static org.geogig.geoserver.config.LogStoreInitializer.runScript;
import static org.geogig.geoserver.config.LogStoreInitializer.saveConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import javax.annotation.Nullable;
import javax.sql.DataSource;

import org.geogig.geoserver.config.LogEvent.Severity;
import org.geoserver.config.GeoServer;
import org.geoserver.config.impl.GeoServerLifecycleHandler;
import org.geoserver.platform.resource.Resource;
import org.geoserver.platform.resource.ResourceStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.db.DBAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.db.DataSourceConnectionSource;
import ch.qos.logback.core.util.StatusPrinter;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;

public class LogStore implements GeoServerLifecycleHandler, InitializingBean {

    private static final char MSG_FIELD_SEPARATOR = '|';

    static final String PROP_ENABLED = "enabled";

    static final String PROP_MAX_CONNECTIONS = "maxConnections";

    static final String PROP_PASSWORD = "password";

    static final String PROP_USER = "user";

    static final String PROP_DRIVER_CLASS = "driverClass";

    static final String PROP_URL = "url";

    static final String PROP_SCRIPT = "initScript";

    static final String PROP_RUN_SCRIPT = "runInitScript";

    static final String CONFIG_DIR_NAME = "geogig/config/security";

    static final String CONFIG_FILE_NAME = "logstore.properties";

    private Logger LOGBACKLOGGER;

    private File configFile;

    private DataSource dataSource;

    private ResourceStore resourceStore;

    private volatile boolean enabled;

    public LogStore(ResourceStore resourceStore) {
        this.resourceStore = resourceStore;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        init();
    }

    /**
     * Called when geoserver is being shutdown
     */
    @Override
    public void onDispose() {
        destroy();
    }

    /**
     * Called as part of {@link GeoServer#reset()} execution to clear up all of the caches inside
     * GeoServer forcing reloading of all information besides the configuration itself
     */
    @Override
    public void onReset() {
        //
    }

    /**
     * Called as {@link GeoServer#reload()} begins its work. A subsequent call to
     * {@link #onReload()} is guaranteed
     */
    @Override
    public void beforeReload() {
        destroy();
    }

    /**
     * Called as part of the {@link GeoServer#reload()} process to clear up all of the caches as
     * well as the configuration information
     */
    @Override
    public void onReload() {
        init();
    }

    void destroy() {
        enabled = false;
        if (dataSource != null) {
            DataSource dataSource = this.dataSource;
            this.dataSource = null;
            this.LOGBACKLOGGER = null;
            this.configFile = null;
            LogStoreInitializer.dispose(dataSource);
        }
    }

    private void init() {
        try {
            this.configFile = findOrCreateConfigFile();
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(configFile)) {
            properties.load(in);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("properties file does not exist: " + configFile);
        } catch (IOException e) {
            throw new RuntimeException("Error loading properties file " + configFile, e);
        }

        boolean enabled = Boolean.valueOf(properties.getProperty(PROP_ENABLED));
        if (enabled) {
            dataSource = newDataSource(properties, configFile);
            boolean runScript = Boolean.valueOf(properties.getProperty(PROP_RUN_SCRIPT));
            if (runScript) {
                String scriptProp = properties.getProperty(PROP_SCRIPT);
                URL script = resolveScript(scriptProp, configFile);
                runScript(dataSource, script);
                // all good, lets disable the script for the next runs
                properties.setProperty(PROP_RUN_SCRIPT, "false");
                saveConfig(properties, configFile);
            }

            LOGBACKLOGGER = createLogger(dataSource);
        }
        this.enabled = enabled;
    }

    private URL resolveScript(String scriptProp, File configFile) {
        File scriptFile = new File(scriptProp);
        if (scriptFile.isAbsolute()) {
            checkArgument(scriptFile.exists(), "Script file %s does not exist", scriptFile);
        }
        // find it relative to config file
        scriptFile = new File(configFile.getParentFile(), scriptProp);
        checkArgument(scriptFile.exists(), "Script file %s does not exist",
                scriptFile.getAbsolutePath());

        try {
            return scriptFile.toURI().toURL();
        } catch (MalformedURLException e) {
            throw Throwables.propagate(e);
        }
    }

    public void debug(@Nullable String repoUrl, @Nullable CharSequence message) {
        if (enabled && message != null) {
            String msg = buildMessage(repoUrl, message);
            LOGBACKLOGGER.debug(msg);
        }
    }

    public void info(@Nullable String repoUrl, @Nullable CharSequence message) {
        if (enabled && message != null) {
            String msg = buildMessage(repoUrl, message);
            LOGBACKLOGGER.info(msg);
        }
    }

    public void error(@Nullable String repoUrl, @Nullable CharSequence message, Throwable exception) {
        if (enabled && message != null) {
            String msg = buildMessage(repoUrl, message);
            LOGBACKLOGGER.error(msg, exception);
        }
    }

    public int getFullSize() {
        try (Connection c = dataSource.getConnection()) {
            String sql = "SELECT count(*) from logging_event";
            try (ResultSet rs = c.createStatement().executeQuery(sql)) {
                rs.next();
                int fullSize = rs.getInt(1);
                return fullSize;
            }
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    /**
     * @param offset unlike JDBC offset, this offset starts at zero, not at one
     */
    public List<LogEvent> getLogEntries(final int offset, final int limit) {
        return getLogEntries(offset, limit, (LogEvent.Severity[]) null);
    }

    /**
     * @param offset unlike JDBC offset, this offset starts at zero, not at one
     * @param limit max number of entries to retrieve
     * @param severity filter logs by severity
     */
    public List<LogEvent> getLogEntries(final int offset, final int limit,
            final @Nullable LogEvent.Severity... severity) {

        checkState(enabled, "LogStore has not been initialized");
        checkArgument(offset >= 0);
        checkArgument(limit >= 0);

        StringBuilder sql = new StringBuilder(
                "SELECT event_id, timestmp, level_string, formatted_message FROM logging_event ");
        if (severity != null) {
            sql.append("WHERE level_string IN(");
            for (Iterator<Severity> it = Arrays.asList(severity).iterator(); it.hasNext();) {
                Severity s = it.next();
                sql.append('\'').append(s.toString()).append('\'');
                if (it.hasNext()) {
                    sql.append(", ");
                }
            }
            sql.append(")");
        }
        sql.append(" ORDER BY event_id DESC LIMIT ").append(limit);
        sql.append(" OFFSET ").append(offset);
        try (Connection c = dataSource.getConnection()) {
            try (ResultSet rs = c.createStatement().executeQuery(sql.toString())) {
                List<LogEvent> events = parseEventList(rs, c);
                return events;
            }
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Nullable
    public String getStackTrace(long eventId) {
        try (Connection c = dataSource.getConnection()) {
            return getStackTrace(eventId, c);
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    private String getStackTrace(long eventId, Connection c) throws SQLException {
        String sql = "SELECT trace_line FROM logging_event_exception WHERE event_id = ? ORDER BY i";
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String traceLine = rs.getString(1);
                    sb.append(traceLine).append('\n');
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private List<LogEvent> parseEventList(ResultSet rs, Connection c) throws SQLException {
        List<LogEvent> list = new LinkedList<LogEvent>();
        long eventId;
        long timestamp;
        String levelString;
        String formattedMessage;
        List<String> msgParts;
        String repoUrl;
        String user;
        String message;
        while (rs.next()) {
            eventId = rs.getLong(1);
            timestamp = rs.getLong(2);
            levelString = rs.getString(3);
            formattedMessage = rs.getString(4);
            msgParts = Splitter.on(MSG_FIELD_SEPARATOR).splitToList(formattedMessage);
            Preconditions.checkState(msgParts.size() == 3, "Unknown message format: %s",
                    formattedMessage);
            repoUrl = msgParts.get(0);
            user = msgParts.get(1);
            message = msgParts.get(2);
            Severity severity = LogEvent.Severity.valueOf(levelString);
            LogEvent e = new LogEvent(eventId, timestamp, severity, repoUrl, user, message);
            list.add(e);
        }
        return list;
    }

    /**
     * @return a string composed of {@code <repoUrl>|<user>|<message>}
     */
    private String buildMessage(@Nullable String repoUrl, @Nullable CharSequence message) {
        return new StringBuilder(url(repoUrl)).append(MSG_FIELD_SEPARATOR).append(user())
                .append(MSG_FIELD_SEPARATOR).append(message).toString();
    }

    private String url(String repoUrl) {
        return Strings.isNullOrEmpty(repoUrl) ? "" : repoUrl;
    }

    private static String user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String name = auth == null ? null : auth.getName();
        if (name == null) {
            name = "anonymous";
        }
        return name;
    }

    private File findOrCreateConfigFile() throws IOException {
        Resource dirResource = resourceStore.get(CONFIG_DIR_NAME);
        File dir = dirResource.dir();
        File configFile = new File(dir, CONFIG_FILE_NAME);

        copySampleInitSript(dir, "mysql.sql");
        copySampleInitSript(dir, "postgresql.sql");
        copySampleInitSript(dir, "postgresql.properties");
        copySampleInitSript(dir, "sqlite.sql");
        copySampleInitSript(dir, "hsqldb.sql");
        copySampleInitSript(dir, "hsqldb.properties");

        if (!configFile.exists()) {
            createDefaultConfig(configFile);
        }
        return configFile;
    }

    private static Logger createLogger(DataSource dataSource) {
        LoggerContext lc = new LoggerContext();
        PatternLayoutEncoder ple = new PatternLayoutEncoder();

        ple.setPattern("%d{\"yyyy-MM-dd'T'HH:mm:ss.SSS\"},%level,%msg%n");
        ple.setContext(lc);
        ple.start();

        DataSourceConnectionSource connectionSource = new DataSourceConnectionSource();
        connectionSource.setContext(lc);
        connectionSource.setDataSource(dataSource);
        connectionSource.start();

        DBAppender dbAppender = new DBAppender();
        dbAppender.setContext(lc);
        dbAppender.setConnectionSource(connectionSource);
        dbAppender.start();

        // ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<ILoggingEvent>();
        // consoleAppender.setContext(lc);
        // consoleAppender.setTarget("System.err");
        // consoleAppender.setEncoder(ple);
        // consoleAppender.start();

        lc.start();

        Logger logger = lc.getLogger(LogStore.class);
        // logger.addAppender(consoleAppender);
        logger.addAppender(dbAppender);
        logger.setLevel(Level.DEBUG);

        StatusPrinter.print(lc);
        return logger;
    }
}
