package top.mcocet.net;

import top.mcocet.i18n.I18n;
import top.mcocet.telemetry.TelemetryHandler;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UDP 遥测接收服务。绑定 0.0.0.0,即监听本机所有网卡。
 * 数据包格式见 PacketDecoder(密文信封),收到后交给 TelemetryHandler 处理。
 */
public class UdpServer implements AutoCloseable {

    private static final Logger log = Logger.getLogger(UdpServer.class.getName());
    private static final int MAX_DATAGRAM = 65535;

    private final int port;
    private final TelemetryHandler handler;
    private final Thread thread;
    private volatile DatagramSocket socket;
    private volatile boolean running = false;

    public UdpServer(int port, TelemetryHandler handler) {
        this.port = port;
        this.handler = handler;
        this.thread = new Thread(this::run, "udp-telemetry");
        this.thread.setDaemon(true);
    }

    public void start() throws Exception {
        socket = new DatagramSocket(new InetSocketAddress(port)); // 0.0.0.0 = 所有网卡
        running = true;
        thread.start();
        log.info(I18n.get("udp.started", port));
    }

    private void run() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                // 拷贝数据,避免异步处理与缓冲复用冲突
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                String sourceIp = packet.getAddress() == null ? "?" : packet.getAddress().getHostAddress();
                handler.handleEnvelope(data, sourceIp);
            } catch (java.net.SocketException e) {
                if (running) {
                    log.log(Level.WARNING, I18n.get("udp.socket_error"), e);
                }
            } catch (Exception e) {
                log.log(Level.WARNING, I18n.get("udp.recv_error"), e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
