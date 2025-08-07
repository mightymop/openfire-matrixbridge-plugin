package de.mopsdom.matrix.utils;

import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.mopsdom.matrix.MatrixTransactionHandlerServlet;
import okhttp3.OkHttpClient;

public class HttpUtils {
	
	private static final Logger Log = LoggerFactory.getLogger(HttpUtils.class);
	
	public static OkHttpClient createUnsafeClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] xcs, String string) {}
                    public void checkServerTrusted(X509Certificate[] xcs, String string) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{}; }
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return new OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                .hostnameVerifier((hostname, session) -> true)
                .build();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
	
	public static OkHttpClient createSafeClient() {
        try {

            return new OkHttpClient.Builder().build();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
	
	public static void sendResult(HttpServletResponse resp, int code, JSONObject body)
	{
		try {
			resp.setStatus(code);
			resp.setContentType("application/json; charset=UTF-8");
			resp.getWriter().write(body.toString());
			resp.getWriter().flush();
		}
		catch (Exception e)
		{
			Log.error(e.getMessage(),e);
			Log.error("Code: "+String.valueOf(code)+" Body: "+(body!=null?body.toString():"not available"));
		}
	}
	
	
}
