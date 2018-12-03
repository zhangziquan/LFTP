import java.awt.event.ActionEvent;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.Vector;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.Timer;

public class MyServer {
    public static void main(String[] args) {
        int port = 5555;
        int serverPort;

        String mode = "";

        int clientPort;
        String filename;
        InetAddress clientAdd;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        try {
            DatagramSocket serverSocket = new DatagramSocket(port);
            System.out.println("Server Started at " + port);
            try {
                while (true) {
                    byte[] buf = new byte[128];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    serverSocket.receive(packet);
                    try {
                        clientAdd = packet.getAddress();
                        clientPort = packet.getPort();
                        String result = new String(packet.getData());
                        filename = result.substring(result.indexOf("file:") + 5).trim();
                        if (result.contains("lsend")) {
                            mode = "lsend";
                        } else if (result.contains("lget")) {
                            mode = "lget";
                        }
                        Runnable runnable = new Handle(clientAdd, clientPort, filename, mode);
                        Thread thread = new Thread(runnable);
                        thread.start();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                // TODO: handle exception
                e.printStackTrace();
            }
            serverSocket.close();
        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }
}

class Handle implements Runnable {

    private static int serverPort;
    private int CLIENT_SEND_PORT = 0;
    private String mode;

    private int clientPort;
    String filename;
    InetAddress clientAdd;
    static int idleport = 5000;
    private int start = 0, end = 19, datacount;
    private int finalack = -1;
    private int acknum = 0;
    private InputStream fileInput;
    private Vector<byte[]> filedata = new Vector<byte[]>();
    private Vector<Timer> timers = new Vector<Timer>();
    private int rwnd = 20;

    private int timeout = 500;
    private boolean retransfer = false;

    static DatagramSocket serveSocket = null;

    Handle(InetAddress clientAdd, int clientPort, String filename, String mode) {
        this.clientAdd = clientAdd;
        this.clientPort = clientPort;
        this.filename = filename;
        this.mode = mode;
    }

    private void sendFile() {
        start = 0;
        end = 10;
        datacount = 0;
        acknum = 0;
        filedata.removeAllElements();

        try {
            byte[] seqData = new byte[20];
            byte[] buffer = new byte[1024];
            serverPort = idleport++;
            DatagramSocket serveSocket = new DatagramSocket(serverPort);
            DatagramPacket dataPacket = new DatagramPacket(new byte[1024 + 20], 1024 + 20);

            dataPacket.setPort(clientPort);
            dataPacket.setAddress(clientAdd);

            fileInput = new FileInputStream(new File(filename));
            int bytenum = fileInput.read(buffer);
            for (int i = start; i <= end; i++) {
                String seq = "Seq:" + datacount;
                System.out.println("Send the " + datacount++ + " packet");
                System.arraycopy(seq.getBytes(), 0, seqData, 0, seq.getBytes().length);
                byte[] packet = byteMerger(seqData, buffer);
                filedata.addElement(packet);
                dataPacket.setData(packet, 0, bytenum + 20);
                serveSocket.send(dataPacket);
                Timer newtimer = new Timer(timeout, new DelayActionListener(serveSocket, i));
                newtimer.start();
                timers.add(newtimer);
                if (i != end) {
                    bytenum = fileInput.read(buffer);
                }
            }

            receiveack(serveSocket);
            dataPacket.setData("end".getBytes(), 0, 3);
            serveSocket.send(dataPacket);

            fileInput.close();

            System.out.println("File transfer is finished! The number of packets is " + datacount);

            System.out.println("finish transfer: " + filename + " to " + clientAdd.toString());

            serveSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void receiveFile() {
        try {

            // say hello to client
            byte[] data = new byte[1024 + 20];
            serverPort = idleport++;
            serveSocket = new DatagramSocket(serverPort);
            DatagramPacket hellopacket = new DatagramPacket(new byte[128], 128);
            hellopacket.setPort(clientPort);
            hellopacket.setAddress(clientAdd);
            hellopacket.setData(("Hello Client, receive file:" + filename).getBytes());
            serveSocket.send(hellopacket);

            DatagramPacket receivePacket = new DatagramPacket(data, data.length);
            serveSocket.receive(receivePacket);
            System.out.println("Downloading...");
            File thefile = new File(filename);
            thefile.createNewFile();
            CLIENT_SEND_PORT = receivePacket.getPort();

            FileOutputStream filepointer = new FileOutputStream(thefile);
            int endReceive = -1;
            // read the file
            int nextseq = 0;
            int count = 0;
            while (new String(receivePacket.getData()) != "end" && receivePacket.getLength() != 3) {
                if (Math.random() <= 1) {
                    byte[] packet = new byte[receivePacket.getLength()];
                    System.arraycopy(receivePacket.getData(), 0, packet, 0, receivePacket.getLength());
                    byte[] seqByte = new byte[20];
                    byte[] buffer = new byte[packet.length - 20];
                    System.arraycopy(packet, 0, seqByte, 0, 20);
                    System.arraycopy(packet, 20, buffer, 0, packet.length - 20);
                    String seq = new String(seqByte);
                    int seqnum = Integer.parseInt(seq.substring(4).trim());
                    System.out.println("the Seq:" + seqnum + " packet come");
                    if (seqnum == nextseq) {
                        System.out.println(count++ + "| Recive the Seq:" + seqnum + " packet");
                        nextseq++;
                        respond(seqnum);
                        filepointer.write(buffer, 0, buffer.length);
                    } else {
                        respond(nextseq - 1);
                    }
                    serveSocket.receive(receivePacket);

                } else {
                    System.out.println("drop the packet,need " + nextseq + " now : ");
                    serveSocket.receive(receivePacket);
                }
            }
            filepointer.close();
            System.out.println("File download is finished! The number of packets is " + count);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    private void respond(Integer endReceive) {
        try {
            byte[] ackData = new String("ack:" + endReceive).getBytes();
            DatagramPacket sendAck = new DatagramPacket(ackData, ackData.length, clientAdd, CLIENT_SEND_PORT);
            serveSocket.send(sendAck);
            System.out.println("client send the ack = " + endReceive);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public void run() {
        if (mode == "lget") {
            sendFile();
        } else {
            receiveFile();
        }
    }

    public static byte[] byteMerger(byte[] bt1, byte[] bt2) {
        byte[] bt3 = new byte[bt1.length + bt2.length];
        System.arraycopy(bt1, 0, bt3, 0, bt1.length);
        System.arraycopy(bt2, 0, bt3, bt1.length, bt2.length);
        return bt3;
    }

    public void receiveack(DatagramSocket serveSocket) {
        while (true) {
            try {
                byte[] recvData = new byte[100];
                DatagramPacket recvPacket = new DatagramPacket(recvData, recvData.length);
                serveSocket.receive(recvPacket);
                int ackseq = Integer.parseInt(new String(recvPacket.getData()).substring(4).trim());
                System.out.println("Server receive ack = " + ackseq);
                if (ackseq == -1) {
                    continue;
                }
                timers.elementAt(acknum - ackseq).stop();
                if (ackseq >= acknum) {
                    if (finalack != -1) {
                        if (ackseq == finalack) {
                            for (int i = 0; i < timers.size(); i++) {
                                timers.elementAt(i).stop();
                            }
                            return;
                        }
                    }
                    int rcnum = acknum - ackseq + 1;
                    acknum = ackseq + 1;
                    for (int i = 0; i < rcnum; i++) {
                        timers.elementAt(0).stop();
                        timers.remove(0);
                        filedata.remove(0);

                        start++;
                        end++;
                        byte[] buffer = new byte[1024];
                        DatagramPacket dataPacket = new DatagramPacket(new byte[1024 + 20], 1024 + 20);
                        int bytenum = fileInput.read(buffer);
                        if (bytenum == -1) {
                            finalack = datacount - 1;
                            break;
                        }
                        String newseq = "Seq:" + datacount;
                        System.out.println("Send the " + datacount++ + " packet");
                        byte[] seqData = new byte[20];
                        System.arraycopy(newseq.getBytes(), 0, seqData, 0, newseq.getBytes().length);
                        byte[] packet = byteMerger(seqData, buffer);
                        filedata.add(packet);

                        dataPacket.setPort(clientPort);
                        dataPacket.setAddress(clientAdd);
                        dataPacket.setData(packet, 0, bytenum + 20);
                        serveSocket.send(dataPacket);
                        Timer newtimer = new Timer(timeout, new DelayActionListener(serveSocket, end));
                        newtimer.start();
                        timers.add(newtimer);
                    }
                } else {

                }
            } catch (Exception e) {
                // TODO: handle exception

                e.printStackTrace();
            }
        }
    }

    class DelayActionListener implements ActionListener {
        DatagramSocket serveSocket;
        int end_ack;

        public DelayActionListener(DatagramSocket serveSocket, int end_ack) {
            this.serveSocket = serveSocket;
            this.end_ack = end_ack;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            int the_end = end;
            int the_start = start;
            System.out.println("Server begin to tranfer the data again " + end_ack + "--" + the_end);
            for (int i = 0; i < timers.size(); i++) {
                try {
                    DatagramPacket dataPacket = new DatagramPacket(new byte[1024 + 20], 1024 + 20);

                    dataPacket.setPort(clientPort);
                    dataPacket.setAddress(clientAdd);
                    dataPacket.setData(filedata.elementAt(i));
                    serveSocket.send(dataPacket);
                    int x = end_ack + i;
                    System.out.println("Server  re - transfer the data " + x);

                    timers.elementAt(i).stop();
                    timers.elementAt(i).start();

                } catch (Exception e1) {
                    // TODO: handle exception
                    e1.printStackTrace();
                }
            }
        }
    }

    /**
     * У���?
     * 
     * @param msg    ��Ҫ����У��͵�byte����
     * @param length У���λ��?
     * @return �������У�������
     */
    private byte[] SumCheck(byte[] msg, int length) {
        long mSum = 0;
        byte[] mByte = new byte[length];

        /** ��Byte���λ����? */
        for (byte byteMsg : msg) {
            long mNum = ((long) byteMsg >= 0) ? (long) byteMsg : ((long) byteMsg + 256);
            mSum += mNum;
        } /** end of for (byte byteMsg : msg) */

        /** λ����ת��ΪByte���� */
        for (int liv_Count = 0; liv_Count < length; liv_Count++) {
            mByte[length - liv_Count - 1] = (byte) (mSum >> (liv_Count * 8) & 0xff);
        } /** end of for (int liv_Count = 0; liv_Count < length; liv_Count++) */

        return mByte;
    }
}