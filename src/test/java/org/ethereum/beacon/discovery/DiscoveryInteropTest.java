/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery;

import static org.ethereum.beacon.discovery.TestUtil.NODE_RECORD_FACTORY_NO_VERIFICATION;
import static org.ethereum.beacon.discovery.TestUtil.TEST_SERIALIZER;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.ethereum.beacon.discovery.TestUtil.NodeInfo;
import org.ethereum.beacon.discovery.database.Database;
import org.ethereum.beacon.discovery.packet.AuthHeaderMessagePacket;
import org.ethereum.beacon.discovery.packet.RandomPacket;
import org.ethereum.beacon.discovery.packet.UnknownPacket;
import org.ethereum.beacon.discovery.scheduler.ExpirationSchedulerFactory;
import org.ethereum.beacon.discovery.scheduler.Schedulers;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.storage.LocalNodeRecordStore;
import org.ethereum.beacon.discovery.storage.NodeBucket;
import org.ethereum.beacon.discovery.storage.NodeBucketStorage;
import org.ethereum.beacon.discovery.storage.NodeTableStorage;
import org.ethereum.beacon.discovery.storage.NodeTableStorageFactoryImpl;
import org.ethereum.beacon.discovery.util.Functions;
import org.junit.jupiter.api.Assertions;
import reactor.core.publisher.Flux;

/**
 * Inter-operational test with Geth. Start it in docker separately
 *
 * <p>You need to build and run Geth discv5 test to interact with. Configure Geth running time in
 * test.sh located in `resources/geth`, after that build docker and run it: <code>
 *   cd discovery/src/test/resources/geth
 *   docker build -t gethv5:1.0 . && docker run --network host -d gethv5:1.0
 * </code>
 *
 * <p>After container starts, fire this test to fall in Geth's side running time and it should pass!
 * You could check Geth test logs by following command:<code>
 *   docker logs <container-id/>
 * </code>
 */
// @Ignore("Requires manual startup, takes a bit to start")
public class DiscoveryInteropTest {
  //  @Test
  @SuppressWarnings({"DoubleBraceInitialization"})
  public void testInterop() throws Exception {
    // 1) start 2 nodes
    NodeInfo nodePair1 = TestUtil.generateNode(40412);
    System.out.println(String.format("Node %s started", nodePair1.getNodeRecord().getNodeId()));
    NodeRecord nodeRecord1 = nodePair1.getNodeRecord();
    NodeRecord nodeRecord2 =
        NODE_RECORD_FACTORY_NO_VERIFICATION.fromBase64(
            "-IS4QHa5-0-OmPRchyyBf9jHIWnQlZXthveUPp5_DoDnMMB0V9ChlzNq_fhFixvIr8xOQcKrYsWjjeIBoUIS8HSuWbgBgmlkgnY0gmlwhH8AAAGJc2VjcDI1NmsxoQMOLLdCQcDE_I6BZvGnmgXVsN2VgTp0sJRSnzF9XDnSNYN1ZHCCdl8"); // Geth node
    NodeTableStorageFactoryImpl nodeTableStorageFactory = new NodeTableStorageFactoryImpl();
    Database database1 = Database.inMemoryDB();
    NodeTableStorage nodeTableStorage1 =
        nodeTableStorageFactory.createTable(
            database1,
            TEST_SERIALIZER,
            (oldSeq) -> nodeRecord1,
            () ->
                new ArrayList<NodeRecord>() {
                  {
                    add(nodeRecord2);
                  }
                });
    NodeBucketStorage nodeBucketStorage1 =
        nodeTableStorageFactory.createBucketStorage(database1, TEST_SERIALIZER, nodeRecord1);
    DiscoveryManagerImpl discoveryManager1 =
        new DiscoveryManagerImpl(
            Optional.empty(),
            nodeTableStorage1.get(),
            nodeBucketStorage1,
            new LocalNodeRecordStore(nodeRecord1, nodePair1.getPrivateKey()),
            nodePair1.getPrivateKey(),
            NODE_RECORD_FACTORY_NO_VERIFICATION,
            Schedulers.createDefault().newSingleThreadDaemon("tasks-1"),
            new ExpirationSchedulerFactory(Executors.newSingleThreadScheduledExecutor()));

    // 3) Expect standard 1 => 2 dialog
    CountDownLatch randomSent1to2 = new CountDownLatch(1);
    CountDownLatch authPacketSent1to2 = new CountDownLatch(1);

    Flux.from(discoveryManager1.getOutgoingMessages())
        .map(p -> new UnknownPacket(p.getPacket().getBytes()))
        .subscribe(
            networkPacket -> {
              // 1 -> 2 random
              if (randomSent1to2.getCount() != 0) {
                RandomPacket randomPacket = networkPacket.getRandomPacket();
                System.out.println("1 => 2: " + randomPacket);
                randomSent1to2.countDown();
              } else if (authPacketSent1to2.getCount() != 0) {
                // 1 -> 2 auth packet with FINDNODES
                AuthHeaderMessagePacket authHeaderMessagePacket =
                    networkPacket.getAuthHeaderMessagePacket();
                System.out.println("1 => 2: " + authHeaderMessagePacket);
                authPacketSent1to2.countDown();
              }
            });

    // TODO: check that we receive correct nodes

    // 4) fire 1 to 2 dialog
    discoveryManager1.start();
    int distance = Functions.logDistance(nodeRecord1.getNodeId(), nodeRecord2.getNodeId());
    discoveryManager1.findNodes(nodeRecord2, distance);

    assertTrue(randomSent1to2.await(1, TimeUnit.SECONDS));
    //    assertTrue(whoareyouSent2to1.await(1, TimeUnit.SECONDS));
    int distance1To2 = Functions.logDistance(nodeRecord1.getNodeId(), nodeRecord2.getNodeId());
    Assertions.assertFalse(nodeBucketStorage1.get(distance1To2).isPresent());
    assertTrue(authPacketSent1to2.await(1, TimeUnit.SECONDS));
    Thread.sleep(1000);
    // 1 sent findnodes to 2, received only (2) in answer
    // 1 added 2 to its nodeBuckets, because its now checked, but not before
    NodeBucket bucketAt1With2 = nodeBucketStorage1.get(distance1To2).get();
    Assertions.assertEquals(1, bucketAt1With2.size());
    Assertions.assertEquals(
        nodeRecord2.getNodeId(), bucketAt1With2.getNodeRecords().get(0).getNode().getNodeId());
  }
}
