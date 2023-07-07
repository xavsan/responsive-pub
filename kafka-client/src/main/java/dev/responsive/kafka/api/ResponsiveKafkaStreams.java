package dev.responsive.kafka.api;

import static dev.responsive.kafka.api.InternalConfigs.getConfigs;
import static dev.responsive.kafka.config.ResponsiveDriverConfig.CLIENT_ID_CONFIG;
import static dev.responsive.kafka.config.ResponsiveDriverConfig.CLIENT_SECRET_CONFIG;
import static dev.responsive.kafka.config.ResponsiveDriverConfig.NUM_STANDBYS_OVERRIDE;
import static dev.responsive.kafka.config.ResponsiveDriverConfig.STORAGE_DATACENTER_CONFIG;
import static dev.responsive.kafka.config.ResponsiveDriverConfig.STORAGE_HOSTNAME_CONFIG;
import static dev.responsive.kafka.config.ResponsiveDriverConfig.STORAGE_PORT_CONFIG;
import static dev.responsive.kafka.config.ResponsiveDriverConfig.TASK_ASSIGNOR_CLASS_OVERRIDE;
import static dev.responsive.kafka.config.ResponsiveDriverConfig.TENANT_ID_CONFIG;

import com.datastax.oss.driver.api.core.CqlSession;
import dev.responsive.db.CassandraClient;
import dev.responsive.kafka.clients.ResponsiveKafkaClientSupplier;
import dev.responsive.kafka.config.ResponsiveDriverConfig;
import dev.responsive.kafka.store.ResponsiveStoreRegistry;
import dev.responsive.utils.SessionUtil;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.streams.KafkaClientSupplier;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.StreamsConfig.InternalConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponsiveKafkaStreams extends KafkaStreams {

  private static final Logger LOG = LoggerFactory.getLogger(ResponsiveKafkaStreams.class);

  private StateListener stateListener;

  private final CqlSession session;
  private final ScheduledExecutorService executor;
  private final Admin admin;

  public static ResponsiveKafkaStreams create(
      final Topology topology,
      final Map<String, Object> configs
  ) {
    final ResponsiveStoreRegistry storeRegistry = new ResponsiveStoreRegistry();
    return connect(
        topology,
        configs,
        new ResponsiveKafkaClientSupplier(configs, storeRegistry),
        storeRegistry
    );
  }

  public static ResponsiveKafkaStreams create(
      final Topology topology,
      final Map<String, Object> configs,
      final KafkaClientSupplier clientSupplier
  ) {
    final ResponsiveStoreRegistry storeRegistry = new ResponsiveStoreRegistry();
    return connect(
        topology,
        configs,
        new ResponsiveKafkaClientSupplier(clientSupplier, configs, storeRegistry),
        storeRegistry
    );
  }

  private static ResponsiveKafkaStreams connect(
      final Topology topology,
      final Map<String, Object> configs,
      final ResponsiveKafkaClientSupplier responsiveClientSupplier,
      final ResponsiveStoreRegistry storeRegistry
  ) {
    final ResponsiveDriverConfig responsiveConfigs = new ResponsiveDriverConfig(configs);

    final InetSocketAddress address = InetSocketAddress.createUnresolved(
        responsiveConfigs.getString(STORAGE_HOSTNAME_CONFIG),
        responsiveConfigs.getInt(STORAGE_PORT_CONFIG)
    );

    final String datacenter = responsiveConfigs.getString(STORAGE_DATACENTER_CONFIG);
    final String clientId = responsiveConfigs.getString(CLIENT_ID_CONFIG);
    final Password clientSecret = responsiveConfigs.getPassword(CLIENT_SECRET_CONFIG);
    final String tenant = responsiveConfigs.getString(TENANT_ID_CONFIG);

    final CqlSession session = SessionUtil.connect(
        address,
        datacenter,
        tenant,
        clientId,
        clientSecret == null ? null : clientSecret.value()
    );

    final Admin admin = responsiveClientSupplier.getAdmin(configs);
    final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(2);
    return new ResponsiveKafkaStreams(
        topology,
        verifiedStreamsConfigs(
            configs,
            new CassandraClient(session),
            admin,
            executor,
            storeRegistry,
            topology.describe()
        ),
        responsiveClientSupplier,
        session,
        admin,
        executor
    );
  }

  private ResponsiveKafkaStreams(
      final Topology topology,
      final StreamsConfig streamsConfigs,
      final ResponsiveKafkaClientSupplier clientSupplier,
      final CqlSession session,
      final Admin admin,
      final ScheduledExecutorService executor
  ) {
    super(topology, streamsConfigs, clientSupplier);
    this.session = session;
    this.admin = admin;
    this.executor = executor;
  }

  private static StreamsConfig verifiedStreamsConfigs(
      final Map<String, Object> configs,
      final CassandraClient cassandraClient,
      final Admin admin,
      final ScheduledExecutorService executor,
      final ResponsiveStoreRegistry storeRegistry,
      final TopologyDescription topologyDescription
  ) {
    final Properties propsWithOverrides = new Properties();
    propsWithOverrides.putAll(configs);
    propsWithOverrides.putAll(getConfigs(cassandraClient, admin, executor, storeRegistry));

    // In this case the default and our desired value are both 0, so we only need to check for
    // accidental user overrides
    final Integer numStandbys = (Integer) configs.get(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG);
    if (numStandbys != null && numStandbys != 0) {
      final String errorMsg = String.format(
          "Invalid Streams configuration value for '%s': got %d, expected '%d'",
          StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG,
          numStandbys,
          NUM_STANDBYS_OVERRIDE
      );
      LOG.error(errorMsg);
      throw new ConfigException(errorMsg);
    }

    // TODO(sophie): finish writing KIP to make this a public StreamsConfig
    final Object o = configs.get(InternalConfig.INTERNAL_TASK_ASSIGNOR_CLASS);
    if (o == null) {
      propsWithOverrides.put(
          InternalConfig.INTERNAL_TASK_ASSIGNOR_CLASS,
          TASK_ASSIGNOR_CLASS_OVERRIDE
      );
    } else if (!TASK_ASSIGNOR_CLASS_OVERRIDE.equals(o.toString())) {
      final String errorMsg = String.format(
          "Invalid Streams configuration value for '%s': got %s, expected '%s'",
          InternalConfig.INTERNAL_TASK_ASSIGNOR_CLASS,
          o,
          TASK_ASSIGNOR_CLASS_OVERRIDE
      );
      LOG.error(errorMsg);
      throw new ConfigException(errorMsg);
    }

    return new StreamsConfig(propsWithOverrides);
  }

  @Override
  public void setStateListener(final StateListener stateListener) {
    super.setStateListener(stateListener);
    this.stateListener = stateListener;
  }

  public StateListener stateListener() {
    return stateListener;
  }

  private void closeClients() {
    session.close();
    admin.close();
    executor.shutdown();
  }

  @Override
  public void close() {
    super.close();
    closeClients();
  }

  @Override
  public boolean close(final Duration timeout) {
    final boolean closed = super.close(timeout);
    closeClients();
    return closed;
  }

  @Override
  public boolean close(final CloseOptions options) {
    final boolean closed = super.close(options);
    closeClients();
    return closed;
  }
}
