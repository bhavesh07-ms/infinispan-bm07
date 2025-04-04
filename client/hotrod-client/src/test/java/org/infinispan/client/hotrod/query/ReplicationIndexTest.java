package org.infinispan.client.hotrod.query;

import static org.infinispan.client.hotrod.test.HotRodClientTestingUtil.registerSCI;
import static org.infinispan.configuration.cache.CacheMode.REPL_SYNC;
import static org.infinispan.configuration.cache.IndexStorage.LOCAL_HEAP;
import static org.testng.AssertJUnit.assertEquals;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.api.annotations.indexing.Indexed;
import org.infinispan.api.annotations.indexing.Text;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.test.FixedServerBalancing;
import org.infinispan.client.hotrod.test.HotRodClientTestingUtil;
import org.infinispan.client.hotrod.test.MultiHotRodServersTest;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoSchema;
import org.infinispan.server.hotrod.HotRodServer;
import org.testng.annotations.Test;

/**
 * Test indexing during state transfer.
 */
@Test(groups = "functional", testName = "client.hotrod.query.ReplicationIndexTest")
public class ReplicationIndexTest extends MultiHotRodServersTest {

   public static final String CACHE_NAME = "test-cache";
   public static final String PROTO_FILE = "file.proto";
   public static final int ENTRIES = 2;

   private final AtomicInteger serverCount = new AtomicInteger(0);

   protected void addNode() throws IOException {
      int index = serverCount.incrementAndGet();

      // Add a new Hot Rod server
      addHotRodServer(getDefaultClusteredCacheConfig(REPL_SYNC));
      EmbeddedCacheManager cacheManager = manager(index - 1);

      // Create a client that goes exclusively to the Hot Rod server
      RemoteCacheManager remoteCacheManager = createClient(index - 1);
      clients.add(remoteCacheManager);

      registerSCI(remoteCacheManager, ReplicationIndexTestSCI.INSTANCE);

      // Add the test caches
      org.infinispan.configuration.cache.ConfigurationBuilder builder = getDefaultClusteredCacheConfig(REPL_SYNC, isTransactional());
      builder.indexing().enable()
            .storage(LOCAL_HEAP)
            .addIndexedEntity("Entity");
      cacheManager.defineConfiguration(CACHE_NAME, builder.build());
   }

   private void killLastNode() {
      int index = serverCount.decrementAndGet();
      clients.remove(index).close();
      killServer(index);
   }

   protected boolean isTransactional() {
      return false;
   }

   protected RemoteCacheManager createClient(int i) {
      HotRodServer server = server(i);
      org.infinispan.client.hotrod.configuration.ConfigurationBuilder clientBuilder = HotRodClientTestingUtil.newRemoteConfigurationBuilder();
      clientBuilder.addServer()
            .host(server.getHost())
            .port(server.getPort())
            .marshaller(new ProtoStreamMarshaller())
            .balancingStrategy(() -> new FixedServerBalancing(server));
      return new RemoteCacheManager(clientBuilder.build());
   }

   @Override
   protected void createCacheManagers() throws Throwable {
      addNode();
   }

   @Indexed
   static class Entity {
      private String name;

      public Entity() {
      }

      static Entity create(String name) {
         Entity entity = new Entity();
         entity.setName(name);
         return entity;
      }

      @Text
      @ProtoField(number = 1)
      public String getName() {
         return name;
      }

      public void setName(String name) {
         this.name = name;
      }
   }

   private long queryCount(String query, RemoteCache<?, ?> remoteCache) {
      return remoteCache.query(query).execute().count().value();
   }

   @Test
   public void testIndexingDuringStateTransfer() throws IOException {
      RemoteCache<Object, Object> remoteCache = clients.get(0).getCache(CACHE_NAME);

      for (int i = 0; i < ENTRIES; i++) {
         remoteCache.put(i, Entity.create("name" + i));
      }

      assertIndexed(remoteCache);

      addNode();

      try {
         waitForClusterToForm(CACHE_NAME);

         RemoteCache<Object, Object> secondRemoteCache = clients.get(1).getCache(CACHE_NAME);
         assertIndexed(secondRemoteCache);
      } finally {
         killLastNode();
      }
   }

   private void assertIndexed(RemoteCache<?, ?> remoteCache) {
      assertEquals(ENTRIES, remoteCache.size());
      assertEquals(ENTRIES, queryCount("FROM Entity", remoteCache));
      assertEquals(1, queryCount("FROM Entity where name:'name1'", remoteCache));
   }

   @ProtoSchema(
         includeClasses = {Entity.class},
         schemaFileName = "test.client.ReplicationIndexTest.proto",
         schemaFilePath = "proto/generated",
         service = false
   )
   public interface ReplicationIndexTestSCI extends GeneratedSchema {
      GeneratedSchema INSTANCE = new ReplicationIndexTestSCIImpl();
   }
}
