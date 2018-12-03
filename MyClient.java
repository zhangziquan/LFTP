import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.sql.Time;
import java.util.Vector;

import javax.swing.Timer;

public class MyClient {
    private static final int DEFAULT_PORT = 8888;
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_FILE = "local.txt";

    private static InetAddress host;
    private static final int SERVER_PORT = 5555;
    private static int SERVER_SEND_PORT = 0;
    private static int SERVER_RECEIVE_PORT = 0;

    private static String mode = "";

    private static DatagramSocket clientSocket = null;

    private static String filename = "test.pdf";

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        filename = DEFAULT_FILE;

        if (args.length > 0) {
            mode = args[0];
        }

        if (args.length > 1) {
            try {
                host = InetAddress.getByName(args[1]);
            } catch (Exception e) {
                // TODO: handle exception
                e.printStackTrace();
            }

        }

        if (args.length > 2) {
            filename = args[2];
        }

        try {
            clientSocket = new DatagramSocket(port);
            long startTime = System.currentTimeMillis();
            sendRequest(mode);
            if (mode.equals("lget")) {
                receiveFile();
            } else if (mode.equals("lsend")) {
                byte[] data = new byte[128];
                DatagramPacket receivePacket = new DatagramPacket(data, data.length);
                clientSocket.receive(receivePacket);
                System.out.println(receivePacket.getData());
                Runnable runnable = new Handlel(host, receivePacket.getPort(), filename);
                Thread thread = new Thread(runnable);
                thread.start();
            }
            clientSocket.close();
            long endTime = System.currentTimeMillis();
            System.out.println("The program runs: " + (endTime - startTime) + " ms.");

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public static void sendRequest(String mode) {
        try {
            DatagramPacket packet = new DatagramPacket(new byte[128], 128);
            packet.setPort(SERVER_PORT);
            packet.setAddress(host);
            packet.setData(("Hello Server, " + mode + "  file:" + filename).getBytes());
            clientSocket.send(packet);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public static void receiveFile() {
        try {
            byte[] data = new byte[1024 + 20];
            DatagramPacket receivePacket = new DatagramPacket(data, data.length);
            clientSocket.receive(receivePacket);
            SERVER_SEND_PORT = receivePacket.getPort();
            System.out.println("Downloading...");
            File thefile = new File(filename);
            thefile.createNewFile();

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
                    clientSocket.receive(receivePacket);

                } else {
                    System.out.println("drop the packet,need " + nextseq + " now : ");
                    clientSocket.receive(receivePacket);
                }
            }
            filepointer.close();
            System.out.println("File download is finished! The number of packets is " + count);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public static void respond(Integer endReceive) {
        try {
            byte[] ackData = new String("ack:" + endReceive).getBytes();
            InetAddress ServerAddress = host;
            DatagramPacket sendAck = new DatagramPacket(ackData, ackData.length, ServerAddress, SERVER_SEND_PORT);
            clientSocket.send(sendAck);
            System.out.println("client send the ack = " + endReceive);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public void sendFile(InetAddress clientAdd, int clientPort, String filename) {

        Runnable runnable = new Handlel(clientAdd, clientPort, filename);
        Thread thread = new Thread(runnable);
        thread.start();
    }
}

class Handlel implements Runnable {

    private static int serverPort;

    private int databyte = 1024;

    private int clientPort;
    String filename;
    InetAddress clientAdd;
    static int idleport = 7000;
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

    Handlel(InetAddress clientAdd, int clientPort, String filename) {
        this.clientAdd = clientAdd;
        this.clientPort = clientPort;
        this.filename = filename;
    }

    public void run() {

        int rwnd = 20;
        int base = 0;// window begin
        int last = 0;
        start = 0;
        end = 10;
        datacount = 0;
        acknum = 0;
        filedata.removeAllElements();

        try {
            byte[] seqData = new byte[20];
            byte[] buffer = new byte[databyte];
            serverPort = idleport++;
            DatagramSocket serveSocket = new DatagramSocket(serverPort);
            DatagramPacket dataPacket = new DatagramPacket(new byte[databyte + 20], databyte + 20);

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
                        byte[] buffer = new byte[databyte];
                        DatagramPacket dataPacket = new DatagramPacket(new byte[databyte + 20], databyte + 20);
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
                    DatagramPacket dataPacket = new DatagramPacket(new byte[databyte + 20], databyte + 20);

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
     * §µ???
     * 
     * @param msg    ???????§µ????byte????
     * @param length §µ???¦Ë??
     * @return ???????§µ???????
     */
    private byte[] SumCheck(byte[] msg, int length) {
        long mSum = 0;
        byte[] mByte = new byte[length];

        /** ??Byte???¦Ë???? */
        for (byte byteMsg : msg) {
            long mNum = ((long) byteMsg >= 0) ? (long) byteMsg : ((long) byteMsg + 256);
            mSum += mNum;
        } /** end of for (byte byteMsg : msg) */

        /** ¦Ë????????Byte???? */
        for (int liv_Count = 0; liv_Count < length; liv_Count++) {
            mByte[length - liv_Count - 1] = (byte) (mSum >> (liv_Count * 8) & 0xff);
        } /** end of for (int liv_Count = 0; liv_Count < length; liv_Count++) */

        return mByte;
    }
}