/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 * This file is part of the aion network project.
 *
 * The aion network project is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * The aion network project is distributed in the hope that it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the aion network project source files.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors to the aion source files in decreasing order of code volume:
 *
 * Aion foundation.
 *
 */

package org.aion.p2p.impl1;

import org.aion.p2p.P2pConstant;
import org.aion.p2p.*;
import org.aion.p2p.impl.TaskRequestActiveNodes;
import org.aion.p2p.impl.TaskUPnPManager;
import org.aion.p2p.impl.comm.Act;
import org.aion.p2p.impl.comm.Node;
import org.aion.p2p.impl.comm.NodeMgr;
import org.aion.p2p.impl.zero.msg.*;
import org.apache.commons.collections4.map.LRUMap;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Chris p2p://{uuid}@{ip}:{port} TODO: 1) simplify id bytest to int, ip
 * bytest to str 2) upnp protocal 3) framing
 */
public final class P2pMgr implements IP2pMgr {

    private final static int PERIOD_SHOW_STATUS = 10000;
    private final static int PERIOD_REQUEST_ACTIVE_NODES = 1000;
    private final static int PERIOD_CONNECT_OUTBOUND = 1000;
    private final static int PERIOD_CLEAR = 20000;
    private final static int PERIOD_UPNP_PORT_MAPPING = 3600000;
    private final static int TIMEOUT_OUTBOUND_CONNECT = 10000;

    private final int maxTempNodes;
    private final int maxActiveNodes;

    private final boolean syncSeedsOnly;
    private final boolean showStatus;
    private final boolean showLog;
    private final boolean printReport;
    private final String reportFolder;
    private final int selfNetId;
    private final String selfRevision;
    private final byte[] selfNodeId;
    private final int selfNodeIdHash;
    private final String selfShortId;
    private final byte[] selfIp;
    private final int selfPort;
    private final boolean upnpEnable;

    private final Map<Integer, List<Handler>> handlers = new ConcurrentHashMap<>();
    private final Set<Short> versions = new HashSet<>();

    private NodeMgr nodeMgr;
    private ServerSocketChannel serverChannel;
    private Selector selector;

    private final static int MAX_WORKER_TASKS = 500;
    private ThreadPoolExecutor workers = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().availableProcessors() * 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_WORKER_TASKS),
            new ThreadFactory() {
                private AtomicInteger cnt = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread gh = new Thread(r, "p2p-worker-" + cnt.incrementAndGet());
                    gh.setPriority(Thread.MIN_PRIORITY);
                    return gh;
                }
            }
    );
    private ScheduledThreadPoolExecutor scheduledWorkers;

    private final Map<Integer, Integer> errCnt = Collections.synchronizedMap(new LRUMap<>(128));

    private int errTolerance;

    private AtomicBoolean start = new AtomicBoolean(true);

    private static ReqHandshake1 cachedReqHandshake1;
    private static ResHandshake1 cachedResHandshake1;

    private final class TaskInbound implements Runnable {
        @Override
        public void run() {
            while (start.get()) {
                // System.out.println("s 1");
                int num;
                try {
                    num = selector.select(1);
                } catch (IOException e) {
                    if (showLog)
                        System.out.println("<p2p inbound-select-io-exception>");
                    continue;
                }

                if (num == 0){
                    continue;
                }

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                // System.out.println("size " + selector.selectedKeys().size());
                while (keys.hasNext()) {
                    // System.out.println("s 2");
                    final SelectionKey sk = keys.next();
                    keys.remove();

                    if (!sk.isValid())
                        continue;

                    if (sk.isAcceptable())
                        accept();

                    if (sk.isReadable())
                        try {
                            read(sk);
                        } catch (IOException e) {
                            //System.out.println("read-io-exception");
                            ((ChannelBuffer)sk.attachment()).reset();
                            nodeMgr.tryDropActiveByChannelId((SocketChannel) sk.channel(), P2pMgr.this);
                        } catch (NullPointerException e){
                            e.printStackTrace();
                            nodeMgr.tryDropActiveByChannelId((SocketChannel) sk.channel(), P2pMgr.this);
                            System.out.println("read-null-exception");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                }
            }
            if (showLog)
                System.out.println("<p2p-pi shutdown>");
        }
    }

    private final class TaskStatus implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName("p2p-ts");
            String status = nodeMgr.showStatus(selfShortId);
            System.out.println(status);
            if (printReport) {
                try {
                    Files.write(Paths.get(reportFolder, System.currentTimeMillis() + "-p2p-report.out"),
                            status.getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private final class TaskConnectPeers implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName("p2p-conns");
            while (start.get()) {
                try {
                    Thread.sleep(PERIOD_CONNECT_OUTBOUND);
                } catch (InterruptedException e) {
                    if (showLog)
                        System.out.println("<p2p-tcp interrupted>");
                }

                if (nodeMgr.activeNodesSize() >= maxActiveNodes) {
                    if (showLog)
                        System.out.println("<p2p-tcp-connect-peer pass max-active-nodes>");

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        if (showLog)
                            System.out.println("<p2p-tcp-interrupted>");
                    }
                    continue;
                }

                Node node;
                try {
                    node = nodeMgr.tempNodesTake();
                    if (node.getIfFromBootList())
                        nodeMgr.addTempNode(node);
                } catch (InterruptedException e) {
                    if (showLog)
                        System.out.println("<p2p-tcp-interrupted>");
                    continue;
                }
                int nodeIdHash = node.getIdHash();
                if (!nodeMgr.getOutboundNodes().containsKey(nodeIdHash) && !nodeMgr.hasActiveNode(nodeIdHash)) {
                    int _port = node.getPort();
                    try {
                        SocketChannel channel = SocketChannel.open();
                        if (showLog)
                            System.out.println("<p2p try-connect-" + node.getIpStr() + " node=" + node.getIdShort() + ">");

                        channel.socket().connect(new InetSocketAddress(node.getIpStr(), _port), TIMEOUT_OUTBOUND_CONNECT);
                        if (channel.finishConnect() && channel.isConnected()) {
                            // channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                            channel.configureBlocking(false);
                            selector.wakeup();
                            SelectionKey sk = channel.register(selector, SelectionKey.OP_READ);
                            ChannelBuffer rb = new ChannelBuffer();
                            rb.nodeIdHash = nodeIdHash;
                            sk.attach(rb);

                            node.refreshTimestamp();
                            node.setChannel(channel);

                            if(nodeMgr.addOutboundNode(node)){
                                try {
                                    workers.execute(new TaskWrite(workers, showLog, node.getIdShort(), channel, cachedReqHandshake1, rb));
                                }  catch(RejectedExecutionException ex){

                                }
                                if (showLog)
                                    System.out.println("<p2p task-connect-success remote=" + node.getIpStr() + ":" + _port + ">");
                            } else {
                                closeSocket(channel, "node-" + nodeIdHash + "-exist-at-outbound");
                                if (showLog)
                                    System.out.println("<p2p task-connect-failed node-exits=" + node.getIdShort() + ">");
                            }
                        }

                    } catch (Exception e) {
                        // SocketTimeoutException, ConnectionException Connection refused
                    }
                }
            }
        }
    }

    private final class TaskClear implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName("p2p-clr");
            while (start.get()) {
                try {
                    Thread.sleep(PERIOD_CLEAR);
                    nodeMgr.timeoutInbound(P2pMgr.this);
                    nodeMgr.timeoutActive(P2pMgr.this);
                    nodeMgr.timeoutOutbound(P2pMgr.this);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * @param _nodeId         byte[36]
     * @param _ip             String
     * @param _port           int
     * @param _bootNodes      String[]
     * @param _upnpEnable     boolean
     * @param _maxTempNodes   int
     * @param _maxActiveNodes int
     * @param _showStatus     boolean
     * @param _showLog        boolean
     */
    public P2pMgr(int _netId, String _revision, String _nodeId, String _ip, int _port, final String[] _bootNodes,
                  boolean _upnpEnable, int _maxTempNodes, int _maxActiveNodes, boolean _showStatus, boolean _showLog,
                  boolean _bootlistSyncOnly, boolean _printReport, String _reportFolder, int _errorTolerance) {
        this.selfNetId = _netId;
        this.selfRevision = _revision;
        this.selfNodeId = _nodeId.getBytes();
        this.selfNodeIdHash = Arrays.hashCode(selfNodeId);
        this.selfShortId = new String(Arrays.copyOfRange(_nodeId.getBytes(), 0, 6));
        this.selfIp = Node.ipStrToBytes(_ip);
        this.selfPort = _port;
        this.upnpEnable = _upnpEnable;
        this.maxTempNodes = _maxTempNodes;
        this.maxActiveNodes = _maxActiveNodes;
        this.showStatus = _showStatus;
        this.showLog = _showLog;
        this.syncSeedsOnly = _bootlistSyncOnly;
        this.printReport = _printReport;
        this.reportFolder = _reportFolder;
        this.errTolerance = _errorTolerance;
        this.nodeMgr = new NodeMgr(_showLog);

        for (String _bootNode : _bootNodes) {
            Node node = Node.parseP2p(_bootNode);
            if (node != null && validateNode(node)) {
                this.nodeMgr.addSeedIp(node.getIpStr());
                nodeMgr.addTempNode(node);
            }
        }

        // construct cached ResHandshake on p2p inited with revision
        cachedResHandshake1 = new ResHandshake1(true, this.selfRevision);

    }

    /**
     * @param _node Node
     * @return boolean
     */
    private boolean validateNode(final INode _node) {
        boolean notNull = _node != null;
        boolean notSelfId = _node.getIdHash() != this.selfNodeIdHash;
        boolean notSameIpOrPort = !(Arrays.equals(selfIp, _node.getIp()) && selfPort == _node.getPort());
        boolean notActive = !nodeMgr.hasActiveNode(_node.getIdHash());
        boolean notOutbound = !nodeMgr.getOutboundNodes().containsKey(_node.getIdHash());
        return notNull && notSelfId && notSameIpOrPort && notActive && notOutbound;
    }

    /**
     * @param _sc SocketChannel
     */
    public void closeSocket(final SocketChannel _sc, String _reason) {
        if (showLog)
            System.out.println("<p2p close-socket reason=" + _reason + ">");

        try {
            _sc.close();
        } catch (IOException e) {
            if (showLog)
                System.out.println("<p2p close-socket-io-exception>");
        }
    }

    private void accept() {
        SocketChannel channel;
        try {
            if (syncSeedsOnly)
                return;

            channel = serverChannel.accept();
            channel.configureBlocking(false);
            // channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            selector.wakeup();
            SelectionKey sk = channel.register(selector, SelectionKey.OP_READ);
            sk.attach(new ChannelBuffer());

            String ip = channel.socket().getInetAddress().getHostAddress();
            int port = channel.socket().getPort();

            Node node = new Node(channel, ip, port);
            if(nodeMgr.isSeedIp(ip))
                node.setIfFromBootList(true);
            this.nodeMgr.addInboundNode(node);

            if (showLog)
                System.out.println("<p2p new-connection=" + ip + ":" + port + ">");

        } catch (IOException e) {
            if (showLog)
                System.out.println("<p2p inbound-accept io-exception>");
        }
    }

    /**
     * @param _sc SocketChannel
     * @throws IOException IOException
     */
    private void readHeader(final SocketChannel _sc, final ChannelBuffer _cb) throws IOException {

        int read;
        while ((read = _sc.read(_cb.headerBuf)) > 0) {
            //System.out.println("[header] read=" + read + " pos=" + _cb.headerBuf.position());
        }

        if (read == -1 && _cb.headerBuf.hasRemaining()){
            throw new IOException("eof-on-header-read");
        }

        if (!_cb.headerBuf.hasRemaining()) {
            _cb.header = Header.decode(_cb.headerBuf.array());
//            System.out.println(
//                "[header read] " + (_cb.isHeaderCompleted() ? "completed" : "incompleted") +
//                " read=" + read +
//                " ver=" + _cb.header.getVer() +
//                " crl=" + _cb.header.getCtrl() +
//                " act=" + _cb.header.getAction() +
//                " len=" + _cb.header.getLen()
//            );
        }
    }

    /**
     * @param _sc SocketChannel
     * @throws IOException IOException
     */
    private void readBody(final SocketChannel _sc, final ChannelBuffer _cb) throws IOException {

        int read;
        while ((read = _sc.read(_cb.bodyBuf)) > 0) {
            //System.out.println("[body] read=" + read + " pos=" + _cb.headerBuf.position() + " len=" + _cb.header.getLen());
        }

        if (read == -1 && _cb.bodyBuf.hasRemaining()) {
            throw new IOException("eof-on-body-read");
        }

        if(!_cb.bodyBuf.hasRemaining())
            _cb.body = _cb.bodyBuf.array();

    }

    /**
     * @param _sk SelectionKey
     * @throws IOException IOException
     */
    private void read(final SelectionKey _sk) throws IOException {

        if (_sk.attachment() == null) {
            throw new IOException("<attachment-null>");
        }
        ChannelBuffer rb = (ChannelBuffer) _sk.attachment();

        // read header
        if (!rb.isHeaderCompleted()) {
            //System.out.println("read header start");
            readHeader((SocketChannel) _sk.channel(), rb);
            //System.out.println("read header end | route " + rb.header.getRoute() + " | len " + rb.header.getLen());
        }

        // read body
        if (rb.isHeaderCompleted() && !rb.isBodyCompleted()) {
            if(rb.bodyBuf == null)
                rb.bodyBuf = ByteBuffer.allocate(rb.header.getLen());
            //System.out.println("read body start");
            readBody((SocketChannel) _sk.channel(), rb);
            //System.out.println("read body end " + rb.bodyBuf.position() + "/" + rb.bodyBuf.capacity());
        }

        if(rb.isHeaderCompleted() && rb.isBodyCompleted()) {

            if(rb.header.getLen() > 0 && (rb.body == null || rb.header.getLen() != rb.body.length))
                throw new IOException("!!!!!!!!!!!! body not match header");

            Header h = rb.header;

            byte[] bodyBytes = rb.body == null ? new byte[0] : Arrays.copyOf(rb.body, rb.body.length);
            rb.reset();
            //byte[] bodyBytes = rb.body;


            short ver = h.getVer();
            byte ctrl = h.getCtrl();
            byte act = h.getAction();

            // print route
            //System.out.println("read " + ver + "-" + ctrl + "-" + act);
            switch(ver){
                case Ver.V0:
                    switch (ctrl) {
                        case Ctrl.NET:
                            handleP2pMsg(_sk, act, bodyBytes);
                            break;
                        default:
                            int route = h.getRoute();
                            if (handlers.containsKey(route)) {
                                handleKernelMsg(rb.nodeIdHash, route, bodyBytes);
                            }
                            break;
                    }
                    break;

                // testing versioning
    //            case Ver.V1:
    //                if(ctrl == 0 && act == 0){
    //                    Hello hello = Hello.decode(bodyBytes);
    //                    if(hello != null)
    //                        System.out.println("v1 hello msg " + hello.getMsg());
    //                }
    //
    //                break;
            }
        }

    }

    /**
     * @return boolean TODO: implementation
     */
    private boolean handshakeRuleCheck(int netId) {

        // check net id
        if (netId != selfNetId)
            return false;

        // check supported protocol versions
        return true;
    }

    /**
     * @param _buffer      ChannelBuffer
     * @param _channelHash int
     * @param _nodeId      byte[]
     * @param _netId       int
     * @param _port        int
     * @param _revision    byte[]
     *                     <p>
     *                     Construct node info after handshake request success
     */
    private void handleReqHandshake(final ChannelBuffer _buffer, int _channelHash, final byte[] _nodeId, int _netId,
                                    int _port, final byte[] _revision) {
        Node node = nodeMgr.getInboundNode(_channelHash);
        if (node != null
            // && node.nodeStats.notBan()
        ) {
            if (handshakeRuleCheck(_netId)) {
                _buffer.nodeIdHash = Arrays.hashCode(_nodeId);
                node.setId(_nodeId);
                node.setPort(_port);

                if (_revision != null) {
                    String binaryVersion;
                    try {
                        binaryVersion = new String(_revision, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        binaryVersion = "decode-fail";
                    }
                    node.setBinaryVersion(binaryVersion);

                    int size = workers.getQueue().size();
                    if (size < MAX_WORKER_TASKS){
                        // System.out.println("worker tasks size " + size);
                        try {
                            workers.submit(new TaskWrite(workers, showLog, node.getIdShort(), node.getChannel(), cachedResHandshake1, _buffer));
                        } catch (RejectedExecutionException ex){}
                    }
                }
                nodeMgr.moveInboundToActive(_channelHash, node.getIdShort(), this);
            } else {
                if (showLog)
                    System.out.println("<p2p incompatible-net-id self=" + this.selfNetId + " remote=" + _netId + ">");
            }
        }
    }

    private void handleResHandshake(int _nodeIdHash, String _binaryVersion) {
        Node node = nodeMgr.getOutboundNodes().get(_nodeIdHash);
        if (node != null
            // && node.nodeStats.notBan()
        ) {
            node.refreshTimestamp();
            node.setBinaryVersion(_binaryVersion);
            nodeMgr.moveOutboundToActive(node.getIdHash(), node.getIdShort(), this);
        }
    }

    /**
     * @param _sk       SelectionKey
     * @param _act      ACT
     * @param _msgBytes byte[]
     */
    private void handleP2pMsg(final SelectionKey _sk, byte _act, final byte[] _msgBytes) {
        ChannelBuffer rb = (ChannelBuffer) _sk.attachment();
        switch (_act) {

            case Act.DISCONNECT:
                break;

            case Act.REQ_HANDSHAKE:
                if (_msgBytes.length > ReqHandshake.LEN) {
                    ReqHandshake1 reqHandshake1 = ReqHandshake1.decode(_msgBytes);
                    if (reqHandshake1 != null) {
                        handleReqHandshake(rb, _sk.channel().hashCode(), reqHandshake1.getNodeId(),
                                reqHandshake1.getNetId(), reqHandshake1.getPort(), reqHandshake1.getRevision());
                    }
                }
                break;

            case Act.RES_HANDSHAKE:
                if (rb.nodeIdHash == 0)
                    return;

                if (_msgBytes.length > ResHandshake.LEN) {
                    ResHandshake1 resHandshake1 = ResHandshake1.decode(_msgBytes);
                    if (resHandshake1 != null && resHandshake1.getSuccess())
                        handleResHandshake(rb.nodeIdHash, resHandshake1.getBinaryVersion());

                }
                break;

            case Act.REQ_ACTIVE_NODES:
                if (rb.nodeIdHash != 0) {
                    Node node = nodeMgr.getActiveNode(rb.nodeIdHash);
                    int size = workers.getQueue().size();
                    if (node != null && size < MAX_WORKER_TASKS) {
                        // System.out.println("worker tasks size " + size);
                        try {
                            workers.execute(new TaskWrite(workers, showLog, node.getIdShort(), node.getChannel(),
                                    new ResActiveNodes(nodeMgr.getActiveNodesList()), rb));
                        } catch (RejectedExecutionException ex) {
                        }
                    }
                }
                break;

            case Act.RES_ACTIVE_NODES:
                if (syncSeedsOnly)
                    break;

                if (rb.nodeIdHash != 0) {
                    Node node = nodeMgr.getActiveNode(rb.nodeIdHash);
                    if (node != null) {
                        node.refreshTimestamp();
                        ResActiveNodes resActiveNodes = ResActiveNodes.decode(_msgBytes);
                        if (resActiveNodes != null) {
                            List<INode> incomingNodes = resActiveNodes.getNodes();
                            for (INode incomingNode : incomingNodes) {
                                if (nodeMgr.tempNodesSize() >= this.maxTempNodes)
                                    return;
                                if (validateNode(incomingNode))
                                    nodeMgr.addTempNode((Node)incomingNode);
                            }
                        }
                    }
                }
                break;

            default:
                if (showLog)
                    System.out.println("<p2p unknown-route act=" + _act + ">");
                break;
        }
    }

    /**
     * @param _nodeIdHash int
     * @param _route      int
     * @param _msgBytes   byte[]
     */
    private void handleKernelMsg(int _nodeIdHash, int _route, final byte[] _msgBytes) {
        Node node = nodeMgr.getActiveNode(_nodeIdHash);
        if (node != null) {
            List<Handler> hs = handlers.get(_route);
            if (hs == null)
                return;

            // generally control routes to kernel
            // TODO:
            if(node.nodeStats.shouldRoute(_route, 500)){
                for (Handler hlr : hs) {
                    if (hlr == null)
                        continue;

                    //System.out.println("I am handle kernel msg !!!!! " + hlr.getHeader().getCtrl() + "-" + hlr.getHeader().getAction() + "-" + hlr.getHeader().getLen());
                    int size = workers.getQueue().size();
                    if (size < MAX_WORKER_TASKS) {
                        // System.out.println("worker tasks size " + size);
                        try {

                            workers.execute(() -> hlr.receive(node.getIdHash(), node.getIdShort(), _msgBytes));
                        } catch (RejectedExecutionException ex) {
                            System.out.println("!!! tasks size: " + workers.getQueue().size() + "/" + MAX_WORKER_TASKS + " !!!");
                        }
                    }
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            selector = Selector.open();

            scheduledWorkers = new ScheduledThreadPoolExecutor(1);

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);

//            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, 1024 * 16 * 2);
            serverChannel.socket().bind(new InetSocketAddress(Node.ipBytesToStr(selfIp), selfPort));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            Thread thrdIn = new Thread(new TaskInbound(), "p2p-in");
            thrdIn.setPriority(Thread.NORM_PRIORITY);
            thrdIn.start();

//            for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
//                Thread thrdOut = new Thread(new TaskSend(), "p2p-out-" + i);
//                thrdOut.setPriority(Thread.NORM_PRIORITY);
//                thrdOut.start();
//            }
//
//            for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
//                Thread t = new Thread(new TaskReceive(), "p2p-worker-" + i);
//                t.setPriority(Thread.NORM_PRIORITY);
//                t.start();
//            }

            if (upnpEnable)
                scheduledWorkers.scheduleWithFixedDelay(new TaskUPnPManager(selfPort), 1, PERIOD_UPNP_PORT_MAPPING,
                        TimeUnit.MILLISECONDS);

            if (showStatus)
                scheduledWorkers.scheduleWithFixedDelay(new TaskStatus(), 2, PERIOD_SHOW_STATUS, TimeUnit.MILLISECONDS);

            if (!syncSeedsOnly)
                scheduledWorkers.scheduleWithFixedDelay(new TaskRequestActiveNodes(this), 5000,
                        PERIOD_REQUEST_ACTIVE_NODES, TimeUnit.MILLISECONDS);

            Thread thrdClear = new Thread(new TaskClear(), "p2p-clear");
            thrdClear.setPriority(Thread.NORM_PRIORITY);
            thrdClear.start();

            Thread thrdConn = new Thread(new TaskConnectPeers(), "p2p-conn");
            thrdConn.setPriority(Thread.NORM_PRIORITY);
            thrdConn.start();

        } catch (IOException e) {
            if (showLog)
                System.out.println("<p2p tcp-server-io-exception>");
        }
    }

    @Override
    public INode getRandom() {
        return nodeMgr.getRandom();
    }

    public int getTempNodesCount() {
        return nodeMgr.tempNodesSize();
    }

    @Override
    public List<INode> getActiveNodes() { return nodeMgr.getActiveNodesList(); }

    public INode getActiveNode(int _nodeIdHash){
        return this.nodeMgr.getActiveNode(_nodeIdHash);
    }

    @Override
    public void register(final List<Handler> _cbs) {
        for (Handler _cb : _cbs) {
            Header h = _cb.getHeader();
            short ver = h.getVer();
            byte ctrl = h.getCtrl();
            if (Ver.filter(ver) != Ver.UNKNOWN && Ctrl.filter(ctrl) != Ctrl.UNKNOWN) {
                if (!versions.contains(ver)) {
                    versions.add(ver);
                }

                int route = h.getRoute();
                List<Handler> routeHandlers = handlers.get(route);
                if (routeHandlers == null) {
                    routeHandlers = new ArrayList<>();
                    routeHandlers.add(_cb);
                    handlers.put(route, routeHandlers);
                } else {
                    routeHandlers.add(_cb);
                }
            }
        }

        List<Short> supportedVersions = new ArrayList<>(versions);
        cachedReqHandshake1 = new ReqHandshake1(selfNodeId, selfNetId, this.selfIp, this.selfPort,
                this.selfRevision.getBytes(), supportedVersions);
    }

    @Override
    public void send(int _nodeIdHashcode, final Msg _msg) {
        Node node = this.nodeMgr.getActiveNode(_nodeIdHashcode);
        if (node != null) {
            SelectionKey sk = node.getChannel().keyFor(selector);
            if (sk != null) {
                Object attachment = sk.attachment();
                int size = workers.getQueue().size();
                if (attachment != null && size < MAX_WORKER_TASKS) {
                    // System.out.println("worker tasks size " + size);
                    try {
                        workers.execute(new TaskWrite(workers, showLog, node.getIdShort(), node.getChannel(), _msg, (ChannelBuffer) attachment));
                    } catch (RejectedExecutionException ex) {}
                }
            }
//            else {
//                connection might dropped already
//                System.out.println( + node.getIdShort() + " " + node.getIpStr());
//            }
        }
    }

    @Override
    public void send(int _id, byte[] _msgBytes) {

    }

    @Override
    public void shutdown() {
        start.set(false);
        scheduledWorkers.shutdownNow();
        nodeMgr.shutdown(this);

        for (List<Handler> hdrs : handlers.values()) {
            hdrs.forEach(Handler::shutDown);
        }
    }

    @Override
    public List<Short> versions() {
        return new ArrayList<>(versions);
    }

    @Override
    public int chainId() {
        return selfNetId;
    }

    @Override
    public void dropActive(int _nodeIdHash) {
        nodeMgr.dropActive(_nodeIdHash, this);
    }

    @Override
    public void errCheck(int nodeIdHashcode, String _displayId) {
        int cnt = (errCnt.get(nodeIdHashcode) == null ? 1 : (errCnt.get(nodeIdHashcode).intValue() + 1));

        if (cnt > this.errTolerance) {
            //ban(nodeIdHashcode);
            errCnt.put(nodeIdHashcode, 0);

            if (showLog)
                System.out.println("<ban node: " + (_displayId == null ? nodeIdHashcode : _displayId) + ">");

        } else {
            errCnt.put(nodeIdHashcode, cnt);
        }
    }

    private void ban(int nodeIdHashcode) {
        nodeMgr.ban(nodeIdHashcode);
        nodeMgr.dropActive(nodeIdHashcode, this);
    }
}
