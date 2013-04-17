package org.witness.informacam.transport;

import info.guardianproject.onionkit.trust.StrongHttpsClient;
import info.guardianproject.onionkit.trust.StrongTrustManager;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.witness.informacam.models.IResult;
import org.witness.informacam.models.connections.IConnection;
import org.witness.informacam.utils.Constants.App.Transport;
import org.witness.informacam.utils.Constants.Models;

import android.content.Context;
import android.util.Log;
import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.client.methods.HttpGet;
import ch.boye.httpclientandroidlib.client.methods.HttpPost;
import ch.boye.httpclientandroidlib.impl.client.DefaultHttpClient;

public class HttpUtility {
	private final static String LOG = Transport.LOG;
	
	//these need to be moved to app preferences or at least app global constants; may want to support other proxy systems
	private final static String PROXY_TYPE = "SOCKS";
	private final static String PROXY_HOST = "127.0.0.1";
	private final static int PROXY_PORT = 9050;

	//single shared static instance so we don't have to reload keystores etc
	private static DefaultHttpClient mHttpClient = null;
	

	private static IResult parseResponse(HttpResponse response) {
		IResult result = new IResult();

		StringBuffer sb = new StringBuffer();
		StringBuffer resultBuffer = new StringBuffer();

		sb.append(response.getStatusLine()).append("\n\n");
		if(String.valueOf(response.getStatusLine()).contains(Transport.Results.OK)) {
			result.code = Integer.parseInt(Transport.Results.OK);
		} else {
			result.code = Integer.parseInt(Transport.Results.FAIL[1]);
		}

		try {
			InputStream is = response.getEntity().getContent();
			for(String line : org.apache.commons.io.IOUtils.readLines(is)) {
				sb.append(line);
				resultBuffer.append(line);
			}

			JSONObject resultObject = (JSONObject) new JSONTokener(resultBuffer.toString()).nextValue();
			if(result.code == Integer.parseInt(Transport.Results.OK)) {
				result.data = resultObject.getJSONObject(Models.IResult.DATA);
				result.responseCode = resultObject.getInt(Models.IResult.RESPONSE_CODE);
			} else {
				result.reason = resultObject.getString(Models.IResult.REASON);
			}

		} catch (IllegalStateException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (IOException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (JSONException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (ClassCastException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		}

		Log.d(LOG, "REQUEST RESULT:\n" + sb.toString());
		return result;
	}

	public IConnection executeHttpGet(final IConnection connection, final Context context) {
		ExecutorService ex = Executors.newFixedThreadPool(100);
		Future<IConnection> future = ex.submit(new Callable<IConnection>() {
			@Override
			public IConnection call() throws Exception {
				
				DefaultHttpClient http = getHttpClientInstance(context);
				
				HttpGet get = new HttpGet(connection.build());
				
				Log.d(LOG, "executing get");

				connection.numTries++;
				connection.result = parseResponse(http.execute(get));
				return connection;
			}

		});
		try {
			return future.get();
		} catch (InterruptedException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
			return null;
		} catch (ExecutionException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
			return null;
		}
	}

	public IConnection executeHttpPost(final IConnection connection, final Context context) {
		ExecutorService ex = Executors.newFixedThreadPool(100);
		Future<IConnection> future = ex.submit(new Callable<IConnection>() {
			@Override
			public IConnection call() throws Exception {

				DefaultHttpClient http = getHttpClientInstance (context);
				
				HttpPost post = new HttpPost(connection.url);
				post = connection.build(post);
				Log.d(LOG, "executing post");

				connection.numTries++;
				connection.result = parseResponse(http.execute(post));
				return connection;
			}

		});
		try {
			return future.get();
		} catch (InterruptedException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
			return null;
		} catch (ExecutionException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
			return null;
		}
	}
	
	private static synchronized DefaultHttpClient getHttpClientInstance (Context context) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, KeyManagementException, UnrecoverableKeyException
	{

		if (mHttpClient == null)
		{
			//this is where we want to chain in memorizingtrustmanager
			StrongTrustManager sTrustManager = new StrongTrustManager(context);
			mHttpClient = new StrongHttpsClient(context,sTrustManager);
			((StrongHttpsClient)mHttpClient).useProxy(true, PROXY_TYPE, PROXY_HOST, PROXY_PORT);
		}
		
		return mHttpClient;
	}
	/*
	public class ISocketFactory extends org.apache.http.conn.ssl.SSLSocketFactory {
		SSLContext sslContext;
		
		public ISocketFactory(KeyStore keyStore, IConnection connection) throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
			super(null);
		
			sslContext = SSLContext.getInstance ("TLS");
			ITrustManager itm = new ITrustManager();
			X509KeyManager[] km = null;

			if(connection.transportCredentials != null) {
				km = itm.getKeyManagers(connection.transportCredentials);
			}

			sslContext.init (km, new TrustManager[] { itm }, new SecureRandom ());
		}
		
		@Override
		public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException, UnknownHostException {
			return sslContext.getSocketFactory().createSocket(socket, host, port, autoClose);
		}
		
		@Override
		public Socket createSocket() throws IOException {
			return sslContext.getSocketFactory().createSocket();
		}
	}

	public class ITrustManager implements X509TrustManager {
		private KeyStore keyStore;
		private X509TrustManager defaultTrustManager;
		private X509TrustManager appTrustManager;

		private InformaCam informaCam;
		private IKeyStore keyStoreManifest;
		
		private boolean hasKeyStore;

		private final static String LOG = Crypto.LOG;

		public ITrustManager() {
			informaCam = InformaCam.getInstance();

			hasKeyStore = loadKeyStore();

			defaultTrustManager = getTrustManager(false);
			appTrustManager = getTrustManager(true);
		}

		private boolean loadKeyStore() {
			try {
				keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
			} catch(KeyStoreException e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			}

			try {
				keyStoreManifest = new IKeyStore();
				keyStoreManifest.inflate(informaCam.ioService.getBytes(IManifest.KEY_STORE_MANIFEST, Type.IOCIPHER));

			} catch(Exception e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			}

			

			try {
				keyStore.load(null, null);
				
				ByteArrayInputStream bais = null;
				try {
					bais = new ByteArrayInputStream(informaCam.ioService.getBytes(keyStoreManifest.path, Type.IOCIPHER));
				} catch(NullPointerException e) {}
				
				if(bais != null && bais.available() > 0) {
					keyStore.load(bais, keyStoreManifest.password.toCharArray());
					return true;
				} else {
					Log.d(LOG, "KEY STORE INITED");
					return false;
				}


			} catch(CertificateException e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			} catch (NoSuchAlgorithmException e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			} catch (IOException e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			}
			
			return false;
		}

		private boolean updateKeyStore(byte[] newKeyStore) {
			if(informaCam.ioService.saveBlob(newKeyStore, new info.guardianproject.iocipher.File(IManifest.KEY_STORE))) {
				keyStoreManifest.lastModified = System.currentTimeMillis();
				informaCam.saveState(keyStoreManifest);

				return true;
			}

			return false;
		}

		private void storeCertificate(X509Certificate[] chain) {
			try {
				for(X509Certificate cert : chain) {
					keyStore.setCertificateEntry(cert.getSubjectDN().toString(), cert);
					Log.d(LOG, "setting certificate entry: " + cert.getSubjectDN().toString());
				}
			} catch(KeyStoreException e) {
				Log.e(Crypto.LOG, "keystore exception: " + e.toString());
			}

			appTrustManager = getTrustManager(true);
			try {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				keyStore.store(baos, keyStoreManifest.password.toCharArray());
				updateKeyStore(baos.toByteArray());
				Log.d(Crypto.LOG, "new key encountered!  length: " + baos.size());
			} catch(KeyStoreException e) {
				Log.e(Crypto.LOG, "keystore exception: " + e.toString());	
			} catch (NoSuchAlgorithmException e) {
				Log.e(Crypto.LOG, "no such algo exception: " + e.toString());
			} catch (IOException e) {
				Log.e(Crypto.LOG, "IOException: " + e.toString());
			} catch (CertificateException e) {
				Log.e(Crypto.LOG, "Certificate Exception: " + e.toString());
			}
		}

		public X509KeyManager[] getKeyManagers(ITransportCredentials transportCredentials) {
			KeyManagerFactory kmf = null;
			KeyManager[] km = null;
			X509KeyManager[] xkm = null;
			try {
				kmf = KeyManagerFactory.getInstance("X509");
				KeyStore xks = KeyStore.getInstance("PKCS12");

				byte[] keyBytes = informaCam.ioService.getBytes(transportCredentials.certificatePath, Type.IOCIPHER);
				ByteArrayInputStream bais = new ByteArrayInputStream(keyBytes);
				xks.load(bais, transportCredentials.certificatePassword.toCharArray());

				kmf.init(xks, keyStoreManifest.password.toCharArray());
				km = kmf.getKeyManagers();
				xkm = new X509KeyManager[km.length];

				for(int x=0;x<km.length;x++) {
					X509KeyManager k = (X509KeyManager) km[x];
					xkm[x] = k;
				}

				return xkm;
			} catch (NoSuchAlgorithmException e) {
				Log.e(Crypto.LOG, e.toString());
				e.printStackTrace();
			} catch (KeyStoreException e) {
				Log.e(Crypto.LOG, e.toString());
				e.printStackTrace();
			} catch (IOException e) {
				Log.e(Crypto.LOG, e.toString());
				e.printStackTrace();
			} catch (CertificateException e) {
				Log.e(Crypto.LOG, e.toString());
				e.printStackTrace();
			} catch (UnrecoverableKeyException e) {
				Log.e(Crypto.LOG, e.toString());
				e.printStackTrace();
			} catch (NullPointerException e) {
				Log.e(Crypto.LOG, e.toString());
				e.printStackTrace();
			}

			return null;

		}

		private X509TrustManager getTrustManager(boolean withKeystore) {
			try {
				TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
				if(withKeystore) {
					tmf.init(keyStore);
				} else {
					tmf.init((KeyStore) null);
				}

				for(TrustManager t : tmf.getTrustManagers()) {
					if(t instanceof X509TrustManager) {
						return (X509TrustManager) t;
					}
				}
			} catch (KeyStoreException e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			} catch (NoSuchAlgorithmException e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			}
			return null;
		}

		private boolean isCertKnown(X509Certificate cert) {
			try {
				return keyStore.getCertificateAlias(cert) != null;
			} catch(KeyStoreException e) {
				return false;
			}
		}

		private boolean isExpiredException(Throwable e) {
			do {
				if(e instanceof CertificateExpiredException)
					return true;
				e = e.getCause();
			} while(e != null);
			return false;
		}

		private void checkCertificateTrusted(X509Certificate[] chain, String authType, boolean isServer) throws CertificateException {
			try {
				if(isServer)
					appTrustManager.checkServerTrusted(chain, authType);
				else
					appTrustManager.checkClientTrusted(chain, authType);
			} catch(CertificateException e) {
				if(isExpiredException(e))
					return;
				if(isCertKnown(chain[0]))
					return;

				try {
					if(isServer)
						defaultTrustManager.checkServerTrusted(chain, authType);
					else
						defaultTrustManager.checkClientTrusted(chain, authType);
				} catch(CertificateException ce) {
					storeCertificate(chain);
				}
			}
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			checkCertificateTrusted(chain, authType, false);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			checkCertificateTrusted(chain, authType, true);
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return defaultTrustManager.getAcceptedIssuers();
		}

	}*/
}
