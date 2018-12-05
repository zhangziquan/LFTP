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
import java.util.Arrays;
import java.util.Vector;

import javax.swing.Timer;

public class MyClient {
    private static final int DEFAULT_PORT = 0;
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_FILE = "local.txt";

    private static Vector<byte[]> filecache;

    private static InetAddress host;
    private static int SERVER_PORT = 8080;
    private static int SERVER_SEND_PORT = 0;
    private static int SERVER_RECEIVE_PORT = 0;

    private static String mode = "";

    private static DatagramSocket clientSocket = null;

    private static String filename = "test.pdf";

    private static int databyte = 1024;
    private static int seqbits = 20;
    private static int checksumbits = 2;
    private static int allbits = databyte + seqbits + checksumbits;

    private static int rwnd = 80;
    private static int overflowcount = 0;
    private static Boolean tranfer = false;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        filename = DEFAULT_FILE;

        filecache = new Vector<byte[]>();

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

        if (args.length == 3) {
            filename = args[2];
        }

        if (args.length == 4) {
            SERVER_PORT = Integer.parseInt(args[2]);
            filename = args[3];
        }

        try {
            clientSocket = new DatagramSocket(port);
            long startTime = System.currentTimeMillis();
            sendRequest(mode);
            if (mode.equals("lget")) {
                tranfer = true;
                Runnable runnable = new Writer(filename);
                Thread thread = new Thread(runnable);
                thread.start();
                receiveFile();
            } else if (mode.equals("lsend")) {
                sendFile(host, filename);
            }
            clientSocket.close();
            long endTime = System.currentTimeMillis();
            System.out.println("The program runs: " + (endTime - startTime) + " ms.");
            while (filecache.size() != 0) {
            }
            tranfer = false;

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public static void sendRequest(String mode) {
        try {
            // say hello to the server and send the port and what do you want to do.
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
            byte[] data = new byte[allbits];
            DatagramPacket receivePacket = new DatagramPacket(data, data.length);

            // receive the pracket
            clientSocket.receive(receivePacket);

            // get the send port, so the client can repond to the server.
            SERVER_SEND_PORT = receivePacket.getPort();
            System.out.println("Downloading...");
            File thefile = new File(filename);
            thefile.createNewFile();
            FileOutputStream filepointer = new FileOutputStream(thefile);

            // read the file
            int nextseq = 0;
            int count = 0;

            // start to receive more packets.
            while (new String(receivePacket.getData()) != "end" && receivePacket.getLength() != 3) {
                if (Math.random() <= 1) {
                    byte[] packet = new byte[receivePacket.getLength()];

                    // get the packet.
                    System.arraycopy(receivePacket.getData(), 0, packet, 0, receivePacket.getLength());
                    byte[] seqByte = new byte[seqbits];
                    byte[] buffer = new byte[packet.length - seqbits - checksumbits];
                    byte[] checksum = new byte[checksumbits];

                    // get the seq, checksum and the data
                    System.arraycopy(packet, 0, seqByte, 0, seqbits);
                    System.arraycopy(packet, seqbits, checksum, 0, checksumbits);
                    System.arraycopy(packet, seqbits + checksumbits, buffer, 0, packet.length - seqbits - checksumbits);

                    // check the data is right or wrong.
                    if (!Arrays.equals(checksum, SumCheck(buffer, checksumbits))) {
                        System.out.println("this packet is bad, retransfer!");
                        clientSocket.receive(receivePacket);
                        continue;
                    }

                    // get the seq and judge if it is need.
                    String seq = new String(seqByte);
                    int seqnum = Integer.parseInt(seq.substring(4).trim());
                    System.out.println("the Seq:" + seqnum + " packet come");

                    // if rigth, send ack.
                    if (seqnum == nextseq) {
                        System.out.println(count++ + "| Recive the Seq:" + seqnum + " packet");
                        nextseq++;
                        respond(seqnum, rwnd);
                        filecache.addElement(buffer);
                        rwnd = 50 - filecache.size();
                        // filepointer.write(buffer, 0, buffer.length);
                    } else {

                        // or send the last ack.
                        respond(nextseq - 1, rwnd);
                    }
                    clientSocket.receive(receivePacket);
                } else {

                    // test the drop packet.
                    System.out.println("drop the packet, need " + nextseq + " now!");
                    clientSocket.receive(receivePacket);
                }
            }

            // download finish.
            filepointer.close();
            System.out.println("File download is finished! The number of packets is " + count);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public static void respond(Integer endReceive, Integer rwnd) {

        // respond the ack to the server.
        try {
            byte[] ackData = new String("ack:" + endReceive + "rwnd:" + rwnd).getBytes();
            InetAddress ServerAddress = host;
            DatagramPacket sendAck = new DatagramPacket(ackData, ackData.length, ServerAddress, SERVER_SEND_PORT);
            clientSocket.send(sendAck);
            System.out.println("client send the ack = " + endReceive);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public static void sendFile(InetAddress clientAdd, String filename) {
        try {
            // the client can send the file to server.
            byte[] data = new byte[128];
            DatagramPacket receivePacket = new DatagramPacket(data, data.length);

            // first it should receive the hello from server and get the recevie port from
            // server.
            clientSocket.receive(receivePacket);
            System.out.println(receivePacket.getData());

            Runnable runnable = new cliHandle(clientAdd, receivePacket.getPort(), filename);
            Thread thread = new Thread(runnable);
            thread.start();

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    private static byte[] SumCheck(byte[] msg, int length) {
        long mSum = 0;
        byte[] mByte = new byte[length];

        for (byte byteMsg : msg) {
            long mNum = ((long) byteMsg >= 0) ? (long) byteMsg : ((long) byteMsg + 256);
            mSum += mNum;
        }

        for (int liv_Count = 0; liv_Count < length; liv_Count++) {
            mByte[length - liv_Count - 1] = (byte) (mSum >> (liv_Count * 8) & 0xff);
        }

        return mByte;
    }

    static class Writer implements Runnable {
        private FileOutputStream filepointer;

        Writer(String filename) {
            try {
                File thefile = new File(filename);
                thefile.createNewFile();
                filepointer = new FileOutputStream(thefile);
            } catch (Exception e) {
                // TODO: handle exception
                e.printStackTrace();
            }
        }

        public void run() {
            try {
                while (tranfer) {
                    while (filecache.size() != 0) {
                        System.out.println("the size of cache is " + filecache.size());
                        byte[] buffer = filecache.get(0);
                        filepointer.write(buffer, 0, buffer.length);
                        filecache.remove(0);
                    }
                    Thread.sleep(5);
                }
            } catch (Exception e) {
                // TODO: handle exception
                e.printStackTrace();
            }
        }
    }
}

// the code of Server
class cliHandle implements Runnable {

    private static int serverPort;

    private int databyte = 1024;
    private int seqbits = 20;
    private int checksumbits = 2;
    int allbits = databyte + seqbits + checksumbits;

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

    static DatagramSocket serveSocket = null;

    cliHandle(InetAddress clientAdd, int clientPort, String filename) {
        this.clientAdd = clientAdd;
        this.clientPort = clientPort;
        this.filename = filename;
    }

    public void run() {
        start = 0;
        end = 20;
        datacount = 0;
        acknum = 0;
        filedata.removeAllElements();

        try {
            byte[] seqData = new byte[seqbits];
            byte[] buffer = new byte[databyte];
            serverPort = idleport++;
            DatagramSocket serveSocket = new DatagramSocket(serverPort);
            DatagramPacket dataPacket = new DatagramPacket(new byte[allbits], allbits);

            dataPacket.setPort(clientPort);
            dataPacket.setAddress(clientAdd);

            try {
                fileInput = new FileInputStream(new File(filename));
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("File is not found! The number of packets is " + datacount);

                System.out.println("finish transfer: " + filename + " to " + clientAdd.toString());

                serveSocket.close();
                return;
            }
            int bytenum = fileInput.read(buffer);
            for (int i = start; i <= end; i++) {
                String seq = "Seq:" + datacount;
                System.out.println("Send the " + datacount++ + " packet");

                // compute the checksum.
                byte[] checksum = SumCheck(buffer, checksumbits);

                // compute the seq.
                System.arraycopy(seq.getBytes(), 0, seqData, 0, seq.getBytes().length);

                // compute the packet.
                byte[] packet = byteMerger(byteMerger(seqData, checksum), buffer);

                // debug
                System.out.println("checksum = " + new String(checksum) + " seq = " + new String(seqData));
                filedata.addElement(packet);
                dataPacket.setData(packet, 0, bytenum + checksumbits + seqbits);
                serveSocket.send(dataPacket);

                // set the timer
                Timer newtimer = new Timer(timeout, new DelayActionListener(serveSocket, i));
                newtimer.start();
                timers.add(newtimer);
                if (i != end) {
                    bytenum = fileInput.read(buffer);
                }
                if (bytenum == -1) {
                    finalack = i;
                    break;
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

    public void receiveack(DatagramSocket serveSocket) {
        while (true) {
            try {

                // get the ack and rwnd from client.
                byte[] recvData = new byte[100];
                DatagramPacket recvPacket = new DatagramPacket(recvData, recvData.length);
                serveSocket.receive(recvPacket);
                String ack = new String(recvPacket.getData());
                int ackseq = Integer.parseInt(ack.substring(4, ack.indexOf("rwnd")).trim());
                int clirwnd = Integer.parseInt(ack.substring(ack.indexOf("rwnd") + 5).trim());
                System.out.println("Server receive ack = " + ackseq + ", the rwnd of client is " + clirwnd
                        + " Now the rwnd of server is " + rwnd);
                if (ackseq == -1) {
                    continue;
                }
                timers.elementAt(acknum - ackseq).stop();
                if (ackseq == acknum - 1) {
                    rwnd--;
                }
                if (ackseq >= acknum) {
                    if (clirwnd < rwnd) {
                        rwnd = clirwnd;
                    } else {
                        rwnd++;
                    }
                    if (finalack != -1) {
                        if (ackseq == finalack) {
                            for (int i = 0; i < timers.size(); i++) {
                                // stop the timer.
                                timers.elementAt(i).stop();
                            }
                            return;
                        }
                    }
                    int rcnum = acknum - ackseq + 1;
                    acknum = ackseq + 1;

                    // more ack
                    for (int i = 0; i < rcnum; i++) {
                        synchronized (timers) {
                            timers.elementAt(0).stop();
                            timers.remove(0);
                            filedata.remove(0);
                        }
                    }

                }
                if (rwnd <= 0) {
                    rwnd = 1;
                }
                System.out.println("cwnd:" + timers.size() + " rwnd:" + rwnd);
                while (timers.size() < rwnd) {

                    start++;
                    end++;

                    // send the new packe

                    byte[] buffer = new byte[databyte];
                    DatagramPacket dataPacket = new DatagramPacket(new byte[allbits], allbits);
                    int bytenum = fileInput.read(buffer);
                    if (bytenum == -1) {
                        finalack = datacount - 1;
                        break;
                    }

                    // compute the checksum
                    byte[] checksum = SumCheck(buffer, checksumbits);

                    String newseq = "Seq:" + datacount;
                    System.out.println("Send the " + datacount++ + " packet");
                    byte[] seqData = new byte[seqbits];
                    System.arraycopy(newseq.getBytes(), 0, seqData, 0, newseq.getBytes().length);
                    byte[] packet = byteMerger(byteMerger(seqData, checksum), buffer);
                    filedata.add(packet);

                    // send the file.
                    dataPacket.setPort(clientPort);
                    dataPacket.setAddress(clientAdd);
                    dataPacket.setData(packet, 0, bytenum + seqbits + checksumbits);
                    serveSocket.send(dataPacket);
                    Timer newtimer = new Timer(timeout, new DelayActionListener(serveSocket, end));
                    newtimer.start();

                    // set a timer
                    synchronized (timers) {
                        timers.add(newtimer);
                    }
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
            // if time out, retransfer the packet in the send filed.
            System.out.println("Server begin to tranfer the data again " + end_ack + "--" + the_end);
            for (int i = 0; i < timers.size(); i++) {
                try {
                    DatagramPacket dataPacket = new DatagramPacket(new byte[allbits], allbits);

                    dataPacket.setPort(clientPort);
                    dataPacket.setAddress(clientAdd);
                    dataPacket.setData(filedata.elementAt(i));
                    serveSocket.send(dataPacket);
                    int x = end_ack + i;
                    System.out.println("Srver  re - transfer the data " + x);

                    // reset the timers.
                    synchronized (timers) {
                        timers.elementAt(i).stop();
                        timers.elementAt(i).start();
                    }

                } catch (Exception e1) {
                    // TODO: handle exception
                    e1.printStackTrace();
                }
            }
        }
    }

    public static byte[] byteMerger(byte[] bt1, byte[] bt2) {
        byte[] bt3 = new byte[bt1.length + bt2.length];
        System.arraycopy(bt1, 0, bt3, 0, bt1.length);
        System.arraycopy(bt2, 0, bt3, bt1.length, bt2.length);
        return bt3;
    }

    private byte[] SumCheck(byte[] msg, int length) {
        long mSum = 0;
        byte[] mByte = new byte[length];
        for (byte byteMsg : msg) {
            long mNum = ((long) byteMsg >= 0) ? (long) byteMsg : ((long) byteMsg + 256);
            mSum += mNum;
        }
        for (int liv_Count = 0; liv_Count < length; liv_Count++) {
            mByte[length - liv_Count - 1] = (byte) (mSum >> (liv_Count * 8) & 0xff);
        }

        return mByte;
    }
}