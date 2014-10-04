package com.naorem.khogen.gcm.server.core;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.PacketInterceptor;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.DefaultPacketExtension;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.PacketExtension;
import org.jivesoftware.smack.provider.PacketExtensionProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.util.StringUtils;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.xmlpull.v1.XmlPullParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naorem.khogen.account.server.model.dto.Account;
import com.naorem.khogen.account.server.model.dto.UserCredential;
import com.naorem.khogen.server.common.GlobalConstants;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLSocketFactory;

public class GCMTest {

	private static final Logger LOGGER = Logger.getLogger(GCMTest.class.getName());
	static {

		ProviderManager.addExtensionProvider(GlobalConstants.GCM_ELEMENT_NAME, GlobalConstants.GCM_NAMESPACE, new PacketExtensionProvider() {
			@Override
			public PacketExtension parseExtension(XmlPullParser parser) throws Exception {
				String json = parser.nextText();
				return new GcmPacketExtension(json);
			}
		});
	}

	private XMPPConnection connection;

	/**
	 * Indicates whether the connection is in draining state, which means that
	 * it will not accept any new downstream messages.
	 */
	protected volatile boolean connectionDraining = false;

	/**
	 * Sends a downstream message to GCM.
	 * 
	 * @return true if the message has been successfully sent.
	 */
	public boolean sendDownstreamMessage(String jsonRequest) throws NotConnectedException {
		if (!connectionDraining) {
			send(jsonRequest);
			return true;
		}
		LOGGER.info("Dropping downstream message since the connection is draining");
		return false;
	}

	/**
	 * Returns a random message id to uniquely identify a message.
	 * 
	 * <p>
	 * Note: This is generated by a pseudo random number generator for
	 * illustration purpose, and is not guaranteed to be unique.
	 */
	public String nextMessageId() {
		return "m-" + UUID.randomUUID().toString();
	}

	/**
	 * Sends a packet with contents provided.
	 */
	public void send(String jsonRequest) throws NotConnectedException {
		Packet request = new GcmPacketExtension(jsonRequest).toPacket();
		connection.sendPacket(request);
	}

	/**
	 * Handles an upstream data message from a device application.
	 * 
	 * <p>
	 * This sample echo server sends an echo message back to the device.
	 * Subclasses should override this method to properly process upstream
	 * messages.
	 */
	protected void handleUpstreamMessage(Map<String, Object> jsonObject) {
		// PackageName of the application that sent this message.
		String category = (String) jsonObject.get("category");
		String from = (String) jsonObject.get("from");
		@SuppressWarnings("unchecked")
		Map<String, String> payload = (Map<String, String>) jsonObject.get("data");
		payload.put("ECHO", "Application: " + category);

		// Send an ECHO response back
		// String echo = createJsonMessage(from, nextMessageId(), payload,
		// "echo:CollapseKey", null, false);
		String message = payload.get("MESSAGE");
		// try {
		// sendDownstreamMessage(echo);
		String controlMessage = payload.get("CONTROL_MESSAGE");
		if (message != null) {
			System.out.println(message);
		}
		if(controlMessage != null) {
			System.out.println("Control Message: "+controlMessage);
		}
		// } catch (NotConnectedException e) {
		// logger.log(Level.WARNING,
		// "Not connected anymore, echo message is not sent", e);
		// }
	}

	/**
	 * Handles an ACK.
	 * 
	 * <p>
	 * Logs a INFO message, but subclasses could override it to properly handle
	 * ACKs.
	 */
	protected void handleAckReceipt(Map<String, Object> jsonObject) {
		String messageId = (String) jsonObject.get("message_id");
		String from = (String) jsonObject.get("from");
		//logger.log(Level.INFO, "handleAckReceipt()");// from: " + from + ", messageId: " + messageId);
	}

	/**
	 * Handles a NACK.
	 * 
	 * <p>
	 * Logs a INFO message, but subclasses could override it to properly handle
	 * NACKs.
	 */
	protected void handleNackReceipt(Map<String, Object> jsonObject) {
		String messageId = (String) jsonObject.get("message_id");
		String from = (String) jsonObject.get("from");
	//	logger.log(Level.INFO, "handleNackReceipt() from: " + from + ", messageId: " + messageId);
	}

	protected void handleControlMessage(Map<String, Object> jsonObject) {
		//logger.log(Level.INFO, "handleControlMessage(): " + jsonObject);
		String controlType = (String) jsonObject.get("control_type");
		if ("CONNECTION_DRAINING".equals(controlType)) {
			connectionDraining = true;
		} else {
			LOGGER.log(Level.INFO, "Unrecognized control type: %s. This could happen if new features are " + "added to the CCS protocol.", controlType);
		}
	}

	/**
	 * Creates a JSON encoded GCM message.
	 * 
	 * @param to
	 *            RegistrationId of the target device (Required).
	 * @param messageId
	 *            Unique messageId for which CCS will send an "ack/nack"
	 *            (Required).
	 * @param payload
	 *            Message content intended for the application. (Optional).
	 * @param collapseKey
	 *            GCM collapse_key parameter (Optional).
	 * @param timeToLive
	 *            GCM time_to_live parameter (Optional).
	 * @param delayWhileIdle
	 *            GCM delay_while_idle parameter (Optional).
	 * @return JSON encoded GCM message.
	 */
	public static String createJsonMessage(String to, String messageId, Map<String, String> payload, String collapseKey, Long timeToLive, Boolean delayWhileIdle) {
		Map<String, Object> message = new HashMap<String, Object>();
		message.put("to", to);
		if (collapseKey != null) {
			message.put("collapse_key", collapseKey);
		}
		if (timeToLive != null) {
			message.put("time_to_live", timeToLive);
		}
		if (delayWhileIdle != null && delayWhileIdle) {
			message.put("delay_while_idle", true);
		}
		message.put("message_id", messageId);
		message.put("data", payload);
		return JSONValue.toJSONString(message);
	}

	/**
	 * Creates a JSON encoded ACK message for an upstream message received from
	 * an application.
	 * 
	 * @param to
	 *            RegistrationId of the device who sent the upstream message.
	 * @param messageId
	 *            messageId of the upstream message to be acknowledged to CCS.
	 * @return JSON encoded ack.
	 */
	protected static String createJsonAck(String to, String messageId) {
		Map<String, Object> message = new HashMap<String, Object>();
		message.put("message_type", "ack");
		message.put("to", to);
		message.put("message_id", messageId);
		return JSONValue.toJSONString(message);
	}

	/**
	 * Connects to GCM Cloud Connection Server using the supplied credentials.
	 * 
	 * @param senderId
	 *            Your GCM project number
	 * @param apiKey
	 *            API Key of your project
	 */
	public void connect(long senderId, String apiKey) throws XMPPException, IOException, SmackException {
		ConnectionConfiguration config = new ConnectionConfiguration(GlobalConstants.GCM_SERVER, GlobalConstants.GCM_PORT);
		config.setSecurityMode(SecurityMode.enabled);
		config.setReconnectionAllowed(true);
		config.setRosterLoadedAtLogin(false);
		config.setSendPresence(false);
		config.setSocketFactory(SSLSocketFactory.getDefault());

		connection = new XMPPTCPConnection(config);
		connection.connect();

		connection.addConnectionListener(new LoggingConnectionListener());

		// Handle incoming packets
		connection.addPacketListener(new PacketListener() {

			@Override
			public void processPacket(Packet packet) {
				//logger.log(Level.INFO, "Received: " + packet.toXML());
				Message incomingMessage = (Message) packet;
				GcmPacketExtension gcmPacket = (GcmPacketExtension) incomingMessage.getExtension(GlobalConstants.GCM_NAMESPACE);
				String json = gcmPacket.getJson();
				try {
					@SuppressWarnings("unchecked")
					Map<String, Object> jsonObject = (Map<String, Object>) JSONValue.parseWithException(json);

					// present for "ack"/"nack", null otherwise
					Object messageType = jsonObject.get("message_type");

					if (messageType == null) {
						// Normal upstream data message
						handleUpstreamMessage(jsonObject);

						// Send ACK to CCS
						String messageId = (String) jsonObject.get("message_id");
						String from = (String) jsonObject.get("from");
						String ack = createJsonAck(from, messageId);
						send(ack);
					} else if ("ack".equals(messageType.toString())) {
						// Process Ack
						handleAckReceipt(jsonObject);
					} else if ("nack".equals(messageType.toString())) {
						// Process Nack
						handleNackReceipt(jsonObject);
					} else if ("control".equals(messageType.toString())) {
						// Process control message
						handleControlMessage(jsonObject);
					} else {
						LOGGER.log(Level.WARNING, "Unrecognized message type (%s)", messageType.toString());
					}
				} catch (ParseException e) {
					LOGGER.log(Level.SEVERE, "Error parsing JSON " + json, e);
				} catch (Exception e) {
					LOGGER.log(Level.SEVERE, "Failed to process packet", e);
				}
			}
		}, new PacketTypeFilter(Message.class));

		// Log all outgoing packets
		connection.addPacketInterceptor(new PacketInterceptor() {
			@Override
			public void interceptPacket(Packet packet) {
				LOGGER.log(Level.INFO, "Sent successfully");// {0}", packet.toXML());
			}
		}, new PacketTypeFilter(Message.class));

		connection.login(senderId + "@gcm.googleapis.com", apiKey);
	}

	public static void main(String[] args) throws Exception {
		final long senderId = 540781517159L; // your GCM sender id
		final String password = "AIzaSyDG42Zug0-QndgI1X3jkV-Z3SNwEI-oUNw";

		GCMTest ccsClient = new GCMTest();

		ccsClient.connect(senderId, password);

		// Send a sample hello downstream message to a device.
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		//String toRegId = "APA91bGLT5NPynOjKdsUZoS7x1ZfYbK3ApqIPRhskN0nAk13e56dpPuyWkY1k85N1PSTCIIRePfuqAzhZOf6KzAHG8palhoZNJALoyKZsZdz_TMLI-TzYsHLRId1Uh0HKTctN_akAiLmWINyhzxLGY0YJK8ZrQIfs5M4QZsanNS6F2oTru2m-zk";
		String toRegId = "APA91bFese3Dk5ZgYcw8d79bC64cIQfugAiA5htPm-XkskpKprIjBztKEwb3oRXVuRjlUu6vQRTLOfwVKloswjj9oEtRRby9q8QDs5tb7wdfqLq7xlg5HVksuRam01LR9lJqavO8bfEgCDHnQSooJcCpLEJZmYXpqTM_bQNdPHvtMedf4xhcAjE";
		System.out.println("Length = "+toRegId.length());
		System.out.println("Length = "+UUID.randomUUID().toString().length());
		Account ac = new Account();
		UserCredential uc = new UserCredential();
		ac.setCredential(uc);
		uc.setAddress(toRegId);
		uc.setId("nkhogen");
		uc.setDisplayId("Naorem Khogendro Singh");
		uc.setPassword("test");
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		new ObjectMapper().writeValue(os, ac);
		System.out.println(os.toString());
		while (true) {
			String messageId = ccsClient.nextMessageId();
			Map<String, String> payload = new HashMap<String, String>();
			payload.put("msgId", messageId);
			System.out.println("\n");
			payload.put("MESSAGE", br.readLine());
			String collapseKey = "sample";

			Long timeToLive = 10000L;
			String message = createJsonMessage(toRegId, messageId, payload, collapseKey, timeToLive, true);
			System.out.println(message);

			//ccsClient.sendDownstreamMessage(message);

			Thread.sleep(1000);
		}
	}

	/**
	 * XMPP Packet Extension for GCM Cloud Connection Server.
	 */
	private static final class GcmPacketExtension extends DefaultPacketExtension {

		private final String json;

		public GcmPacketExtension(String json) {
			super(GlobalConstants.GCM_ELEMENT_NAME, GlobalConstants.GCM_NAMESPACE);
			this.json = json;
		}

		public String getJson() {
			return json;
		}

		@Override
		public String toXML() {
			return String.format("<%s xmlns=\"%s\">%s</%s>", GlobalConstants.GCM_ELEMENT_NAME, GlobalConstants.GCM_NAMESPACE, StringUtils.escapeForXML(json), GlobalConstants.GCM_ELEMENT_NAME);
		}

		public Packet toPacket() {
			Message message = new Message();
			message.addExtension(this);
			return message;
		}
	}

	private static final class LoggingConnectionListener implements ConnectionListener {

		@Override
		public void connected(XMPPConnection xmppConnection) {
			LOGGER.info("Connected.");
		}

		@Override
		public void authenticated(XMPPConnection xmppConnection) {
			LOGGER.info("Authenticated.");
		}

		@Override
		public void reconnectionSuccessful() {
			LOGGER.info("Reconnecting..");
		}

		@Override
		public void reconnectionFailed(Exception e) {
			LOGGER.log(Level.INFO, "Reconnection failed.. ", e);
		}

		@Override
		public void reconnectingIn(int seconds) {
			LOGGER.log(Level.INFO, "Reconnecting in %d secs", seconds);
		}

		@Override
		public void connectionClosedOnError(Exception e) {
			LOGGER.info("Connection closed on error.");
		}

		@Override
		public void connectionClosed() {
			LOGGER.info("Connection closed.");
		}
	}

}