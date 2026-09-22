import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Quick check: TLS handshake to S3 through the local proxy using the generated truststore. */
public class TlsProxyCheck {
    public static void main(String[] args) throws Exception {
        URL url = new URL("https://s3-eu-central-1.amazonaws.com");
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", 3128));
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(proxy);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("GET");
        System.out.println("HTTP status: " + connection.getResponseCode());
        System.out.println("Peer: " + connection.getServerCertificates()[0].getType());
        connection.disconnect();
        System.out.println("TLS OK");
    }
}

