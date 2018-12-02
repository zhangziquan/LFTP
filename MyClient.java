import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
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

public class MyClient {
    private static final int DEFAULT_PORT = 8888;
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_FILE = "local.txt";

    private static final int SERVER_PORT = 5555;
    private static int SERVER_SEND_PORT = 0;

    private static DatagramSocket clientSocket = null;

    private static String filename = "test.pdf";

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        filename = DEFAULT_FILE;

        if (args.length > 0) {
            host = args[0];
        }

        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        }

        if (args.length > 2) {
            filename = args[2];
        }

        try {
            clientSocket = new DatagramSocket(port);
            long startTime = System.currentTimeMillis();
            sendRequest();
            receiveFile();
            clientSocket.close();
            long endTime = System.currentTimeMillis();
            System.out.println("The program runs: " + (endTime - startTime) + " ms.");

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public static void sendRequest() {
        try {
            DatagramPacket packet = new DatagramPacket(new byte[128], 128);
            packet.setPort(SERVER_PORT);
            packet.setAddress(InetAddress.getByName("localhost"));
            packet.setData(("Hello Server, file:" + filename).getBytes());
            clientSocket.send(packet);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public static void receiveFile() {
        try {
            byte[] data = new byte[1024 + 10];
            DatagramPacket receivePacket = new DatagramPacket(data, data.length);
            clientSocket.receive(receivePacket);
            SERVER_SEND_PORT = receivePacket.getPort();
            System.out.println("Downloading...");
            File thefile = new File("another" + filename);
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
                    byte[] seqByte = new byte[10];
                    byte[] buffer = new byte[packet.length - 10];
                    System.arraycopy(packet, 0, seqByte, 0, 10);
                    System.arraycopy(packet, 10, buffer, 0, packet.length - 10);
                    String seq = new String(seqByte);
                    int seqnum = Integer.parseInt(seq.substring(4).trim());
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
            InetAddress ServerAddress = InetAddress.getByName("localhost");
            DatagramPacket sendAck = new DatagramPacket(ackData, ackData.length, ServerAddress, SERVER_SEND_PORT);
            clientSocket.send(sendAck);
            System.out.println("client send the ack = " + endReceive);

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }
}