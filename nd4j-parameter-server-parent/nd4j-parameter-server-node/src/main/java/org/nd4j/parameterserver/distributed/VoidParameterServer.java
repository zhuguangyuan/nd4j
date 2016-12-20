package org.nd4j.parameterserver.distributed;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.Pair;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.aggregates.Aggregate;
import org.nd4j.linalg.api.ops.aggregates.Batch;
import org.nd4j.linalg.api.ops.aggregates.impl.AggregateSkipGram;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.parameterserver.distributed.conf.Configuration;
import org.nd4j.parameterserver.distributed.enums.NodeRole;
import org.nd4j.parameterserver.distributed.logic.*;
import org.nd4j.parameterserver.distributed.messages.*;
import org.nd4j.parameterserver.distributed.messages.aggregations.VectorAggregation;
import org.nd4j.parameterserver.distributed.messages.aggregations.VoidAggregation;
import org.nd4j.parameterserver.distributed.messages.intercom.DistributedVectorMessage;
import org.nd4j.parameterserver.distributed.transport.MulticastTransport;
import org.nd4j.parameterserver.distributed.transport.Transport;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This is "special case" distributed P2P parameter server implementation, suitable for Spark Word2Vec/ParagraphVectors/DeepWalk implementations.
 * Aeron is used as backbone for messaging system here.
 *
 * Highlights:
 * a) It does ONLY one-way messaging. Clients are NOT getting anything back from ParamServer.
 * b) It works sharded. Amount of shards is defined in runtime.
 * c) Data replication strategy is defined in runtime.
 * d) It's supposed to be used as singleton in Spark environment ONLY.
 *
 * @author raver119@gmail.com
 */
@Slf4j
public class VoidParameterServer {
    private static final VoidParameterServer INSTANCE = new VoidParameterServer();

    @Getter protected volatile NodeRole nodeRole;

    protected volatile Configuration configuration;


    protected AtomicBoolean initLocker = new AtomicBoolean(false);
    protected AtomicBoolean initFinished = new AtomicBoolean(false);
    protected AtomicBoolean shutdownLocker = new AtomicBoolean(false);
    protected AtomicBoolean shutdownFinished = new AtomicBoolean(false);

    protected transient Transport transport;

    protected transient AtomicBoolean manualMode = new AtomicBoolean(false);
    protected transient AtomicBoolean runner = new AtomicBoolean(false);

    protected transient Thread processingThread;

    protected Connector connector;

    protected short shardIndex;

    protected Clipboard clipboard = new Clipboard();

    protected Storage storage = new WordVectorStorage();


    ////////////////////// SeqVec part

    protected static double MAX_EXP = 6;

    ////////////////////// end of SeqVec part


    protected VoidParameterServer() {
        nodeRole = NodeRole.NONE;
    }

    protected VoidParameterServer(boolean manualMode) {
        this();
        this.manualMode.set(manualMode);
    }

    public static VoidParameterServer getInstance() {
        return INSTANCE;
    }

    /**
     * This method returns shardIndex value.
     * If current node is Shard - it reutrns it's value
     * If current node is client - it returns Shard index of paired Shard
     * @return
     */
    public short getShardIndex() {
        return shardIndex;
    }

    protected void setShardIndex(short idx) {
        shardIndex = idx;
    }

    protected Transport getTransport() {
        return transport;
    }

    protected INDArray getSyn0() {
        return storage.getArray(WordVectorStorage.SYN_0);
    }

    protected INDArray getSyn1() {
        return storage.getArray(WordVectorStorage.SYN_1);
    }

    protected INDArray getSyn1Neg() {
        return storage.getArray(WordVectorStorage.SYN_1_NEGATIVE);
    }

    protected INDArray getExpTable() {
        return storage.getArray(WordVectorStorage.EXP_TABLE);
    }

    protected INDArray getNegTable() {
        return storage.getArray(WordVectorStorage.NEGATIVE_TABLE);
    }

    public void init(@NonNull Configuration configuration) {
        init(configuration, new MulticastTransport());
    }

    /**
     * This method starts ParameterServer instance
     *
     * PLEASE NOTE: This method is blocking for first caller only
     */
    public void init(@NonNull Configuration configuration, Transport transport){
        /**
         * Basic plan here:
         *      start publishers/listeners/subscribers
         *      determine role of the current instance:
         *          Shard
         *          Backup
         *          Client
         *      shutdown unwanted aeron helpers (according to role)
         *      wait for incoming task queries (according to role
         *
         */
        if (initFinished.get())
            return;

        synchronized (this) {
            if (initLocker.compareAndSet(false, true)) {
                this.configuration = configuration;

                this.transport = transport;

                // first we need to check, if our current IP matches designated shards or backup
                if (configuration.getForcedRole() == null || configuration.getForcedRole() == NodeRole.NONE) {
                    Pair<NodeRole, String> pair = getRole(configuration, getLocalAddresses());
                    nodeRole = pair.getFirst();

                    this.transport.init(configuration, clipboard, nodeRole, pair.getSecond());

                } else {

                    nodeRole = configuration.getForcedRole();
                    this.transport.init(configuration, clipboard, nodeRole, "127.0.0.1");
                }


                // TODO: we need real ip only if this is a shard *FOR NOW*, but later we'll need it for client as well

                // we launch message processing if we're not in debug mode
                if (manualMode.get()) {
                    processingThread = new Thread(() -> {
                        runner.set(true);
                        while (runner.get())
                            handleMessage(transport.takeMessage());
                    });
                    processingThread.setDaemon(true);
                    processingThread.setName("VoidParameterServer messages handling thread");
                    processingThread.start();
                }

                initFinished.set(true);
            }
        }

        // TODO: uncomment this line on later stages
        //transport.launch(Transport.ThreadingModel.DEDICATED_THREADS);
    }

    /**
     * This method is available for debug purposes only
     *
     * @param mode
     */
    protected VoidParameterServer toggleManualMode(boolean mode) {
        manualMode.set(mode);
        return this;
    }

    /**
     * This method checks for designated role, according to local IP addresses and configuration passed into method
     *
     * @param configuration
     * @param localIPs
     * @return
     */
    protected Pair<NodeRole, String> getRole(@NonNull Configuration configuration, @NonNull Collection<String> localIPs) {
        NodeRole result = NodeRole.CLIENT;

        for (String ip: localIPs) {
            if (configuration.getShardAddresses().contains(ip))
                return Pair.create(NodeRole.SHARD, ip);
        }

        if (configuration.getBackupAddresses() != null)
            for (String ip: localIPs)
                if (configuration.getBackupAddresses().contains(ip))
                    return Pair.create(NodeRole.BACKUP, ip);


        // local IP from pair is used for shard only, so we don't care
        return Pair.create(result, "127.0.0.1");
    }

    /**
     * This method initiates shutdown sequence for this instance.
     *
     * PLEASE NOTE: This method is blocking for first caller only
     */
    public void shutdown() {
        /**
         * Probably we don't need this method in practice
         */
        if (initLocker.get() && shutdownLocker.compareAndSet(false, true)) {
            // do shutdown
            log.info("Shutting down transport...");
            transport.shutdown();
        }
    }

    /**
     * This method returns set of local IP addresses available in system.
     *
     * PLEASE NOTE: loopback, disabled interfaces, IPv6 addresses are ignored here.
     *
     * @return
     */
    public static Set<String> getLocalAddresses() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());

            Set<String> result = new HashSet<>();

            for (NetworkInterface networkInterface : interfaces) {
                if (networkInterface.isLoopback() || !networkInterface.isUp())
                    continue;

                for(InterfaceAddress address: networkInterface.getInterfaceAddresses()) {
                    String addr = address.getAddress().getHostAddress();

                    if (addr == null || addr.isEmpty() || addr.contains(":"))
                        continue;

                    result.add(addr);
                }
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    // TODO: remove @NonNull check here
    protected void handleMessage(@NonNull VoidMessage message) {
        if (message == null)
            return;

        log.info("Processing message: {}", message.getMessageType());

        message.attachContext(configuration, transport, storage, nodeRole, shardIndex);
        message.processMessage();

        /*
        switch (message.getMessageType()) {
            // initialization message
            case 4: {
                    initializeSeqVec((InitializationMessage) message);
                }
                break;
            // share solid, propagates array among all Shard/Backup nodes
            case 5: {
              //      negTable = ((ShareSolidMessage) message).getPayload();
                }
                break;
            // assign
            case 6: {
                    AssignMessage assign = (AssignMessage) message;
                    if (assign.getIndex() >= 0) {
               //         syn0.getRow(assign.getIndex()).assign(assign.getValue());
                    } else {
               //         syn0.assign(assign.getValue());
                    }
                }
                break;
            // joint vector request for specific
            case 7: {
                    //
                }
                break;
            case 20: {
                    DistributedVectorMessage dvm = (DistributedVectorMessage) message;

                    log.info("Got distributed request for rowIndex: {}", dvm.getRowIndex());
              //      VectorAggregation aggregation = new VectorAggregation(dvm.getRowIndex(), (short) configuration.getNumberOfShards(), getShardIndex(), syn0.getRow(dvm.getRowIndex()).dup());
              //      transport.sendMessageToAllShards(aggregation);
                }
                break;
            case 21: {
                    VoidAggregation aggregation = (VoidAggregation) message;
                    if (clipboard.isTracking(aggregation.getTaskId())) {
                        clipboard.pin(aggregation);
                    } else {
                        log.info("Skipping vectors. Shard: {}; ", getShardIndex());
                    }
                }
                break;
            default:
                log.info("Unknown messageType [{}] being passed in...", message.getMessageType());
        }
        */
    }

    /**
     * This method handles Shard initialization
     *
     * @param message
     */
    // TODO: right now we support only columnar splits over tables
    protected void initializeSeqVec(@NonNull InitializationMessage message) {

    }


    /**
     * This method takes AggregateOp, and sends them for execution over network
     *
     * PLEASE NOTE: This method is NOT blocking
     *
     * @param aggregate
     */
    public <T extends Aggregate> void execDistributed(@NonNull T aggregate) {
        // we just form the message and send it


        transport.sendMessage(null);
    }

    /**
     * This method takes Batch of Aggregates, and executes them over network
     *
     * PLEASE NOTE: This method is NOT blocking
     *
     * @param batch
     * @param <T>
     */
    public <T extends Aggregate> void execDistributed(@NonNull Batch<T> batch) {
        // we form it and send it :)

        transport.sendMessage(null);
    }


    /**
     * This method returns INDArray matching requested storageId value
     *
     * PLEASE NOTE: This method IS blocking
     *
     * @param rowId
     * @return
     */
    public INDArray getVector(int rowId) {
        /**
         * we create VoidMessage, send it, and block until it gets responded
         */

        VectorRequestMessage message = new VectorRequestMessage(rowId);

        transport.sendMessage(message);



        return null;
    }
}