package de.mopsdom.matrix;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.Message;

import de.mopsdom.matrix.utils.HttpUtils;
import de.mopsdom.matrix.utils.Utils;
import de.mopsdom.xmpp.MatrixBridgePlugin;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MatrixAPI {

	private static final Logger Log = LoggerFactory.getLogger(MatrixAPI.class);

	private Cache<String, Boolean> whoamiCache;
	private Cache<String, String> roomCache;
	private Cache<String, Boolean> joinedRoomCache;
	private Cache<String, String> publishedMucCache;

	private OkHttpClient client;

	private static MatrixAPI instance = null;

	public MatrixAPI() {
		instance = this;
		client = MatrixBridgePlugin.HTTP_IGNORE_SSL.getValue() ? HttpUtils.createUnsafeClient()
				: HttpUtils.createSafeClient();

		whoamiCache = CacheFactory.createCache("MatrixApiWhoami");
		roomCache = CacheFactory.createCache("MatrixApiRoom");
		joinedRoomCache = CacheFactory.createCache("MatrixApiJoinedRooms");
		publishedMucCache = CacheFactory.createCache("MatrixApiPublishedRooms");
	}

	public static MatrixAPI getInstance() {
		if (instance == null)
			instance = new MatrixAPI();

		return instance;
	}

	private Request.Builder authRequest(String path) {
		return new Request.Builder().url(MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + path)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue());
	}

	public boolean canActAsUser(String mxid) {
		if (whoamiCache.containsKey(mxid)) {
			return whoamiCache.get(mxid);
		}

		try {
			if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
			{
				Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
				return false;
			}
			
			HttpUrl url = HttpUrl
					.parse(MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + "/_matrix/client/v3/account/whoami")
					.newBuilder().addQueryParameter("user_id", mxid).build();

			Request request = new Request.Builder().url(url)
					.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue()).get()
					.build();

			try (Response response = client.newCall(request).execute()) {
				if (response.isSuccessful()) {
					JSONObject json = new JSONObject(response.body().string());
					String returnedUser = json.optString("user_id", null);
					boolean allowed = mxid.equals(returnedUser);
					whoamiCache.put(mxid, allowed);
					return allowed;
				} else {
					Log.warn("Matrix whoami request failed for {}: HTTP {} - {}", mxid, response.code(),
							response.body().string());
					whoamiCache.put(mxid, false);
					return false;
				}
			}
		} catch (Exception e) {
			Log.error("Matrix whoami check failed for {}: {}", mxid, e.getMessage(), e);
			whoamiCache.put(mxid, false);
			return false;
		}
	}

	public void sendMessageToUser(Message message) {

		String xmppSender = message.getFrom().getNode(); // z.B. user1
		String xmppRecipient = message.getTo().getNode(); // z.B. user2

		String matrixSender = Utils.convertXmppUserJIDToMatrixID(message.getFrom(), "@xmpp_"); // z.B.
																								// @user1:matrix.local
		String matrixRecipient = Utils.convertXmppUserJIDToMatrixID(message.getTo(), "@xmpp_"); // z.B.
																								// @user2:matrix.local

		// Räume werden anhand der Teilnehmer eindeutig benannt
		String roomAliasLocalpart = xmppSender + "_bridge_" + xmppRecipient;
		String roomAlias = "#" + roomAliasLocalpart + ":" + message.getTo().getDomain();
		String roomId = null;

		try {
			// 1. Prüfen ob Alias existiert → Raum-ID holen
			roomId = resolveRoomAlias(roomAlias);
			if (roomId==null)
			{
				try {
					// 2. Raum erzeugen (wenn nicht vorhanden)
					roomId = createRoom(roomAlias, matrixRecipient, true);
				} catch (IOException ce) {
					Log.error("Failed to create room: " + ce.getMessage());
					return;
				}
			}

		} catch (FileNotFoundException e) {
			Log.info("Room alias does not exist. Creating new room: " + roomAlias);

			try {
				// 2. Raum erzeugen (wenn nicht vorhanden)
				roomId = createRoom(roomAlias, matrixRecipient, true);
			} catch (IOException ce) {
				Log.error("Failed to create room: " + ce.getMessage());
				return;
			}
		} catch (IOException e) {
			return;
		}

		if (roomId==null)
		{
			Log.error("roomId = null, abort sending message");
		}
		// 3. Sender joinen lassen (virtueller User = masquerade via user_id)
		try {
			joinRoomIfNecessary(roomId, matrixSender);
		} catch (IOException je) {
			Log.warn("Sender could not join room: " + je.getMessage());
			return;
		}

		// 4. Zielnutzer einladen (falls noch nicht im Raum)
		try {
			if (!isUserInRoom(roomId, matrixRecipient, matrixSender)) {
				inviteUserToRoom(roomId, matrixRecipient, matrixSender);
			}
		} catch (IOException ie) {
			Log.warn("Failed to invite recipient: " + ie.getMessage());
		}

		// 5. Nachricht vorbereiten
		String body = message.getBody();
		if (body == null || body.trim().isEmpty()) {
			Log.warn("Message has no body");
			return;
		}

		JSONObject msg = new JSONObject();
		msg.put("msgtype", "m.text");
		msg.put("body", body);

		String txnId = message.getID() != null ? message.getID() : UUID.randomUUID().toString();
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return;
		}

		HttpUrl url = HttpUrl
				.parse(MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + "/_matrix/client/v3/rooms/" + roomId
						+ "/send/m.room.message/" + txnId)
				.newBuilder().addQueryParameter("user_id", matrixSender) // masquerade
				.build();

		Request request = new Request.Builder().url(url)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue())
				.post(RequestBody.create(msg.toString(), MediaType.parse("application/json"))).build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				String errorBody = response.body() != null ? response.body().string() : "null";
				Log.error("Matrix send message failed: " + errorBody);
			}
		} catch (IOException e) {
			Log.error("Error sending message to Matrix: " + e.getMessage());
		}
	}

	private String getLocalAliasPart(String roomAlias) {
		return roomAlias.split(":")[0].substring(1);
	}

	public String resolveRoomAlias(String roomAlias) throws IOException, FileNotFoundException {
		if (roomCache.containsKey(roomAlias)) {
			return roomCache.get(roomAlias);
		}
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return null;
		}

		HttpUrl url = HttpUrl.parse(MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()
				+ "/_matrix/client/v3/directory/room/" + URLEncoder.encode(roomAlias, StandardCharsets.UTF_8))
				.newBuilder().build();

		Request request = new Request.Builder().url(url)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue()).get().build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				// Matrix gibt z. B. 404, wenn Raum nicht existiert
				if (response.code() == 404) {
					Log.info("Room alias not found: " + roomAlias);
					throw new FileNotFoundException("Room alias not found: " + roomAlias);
				} else {
					Log.error("Failed to resolve alias (" + roomAlias + "): " + response.code());
					throw new IOException("Failed to resolve alias (" + roomAlias + "): " + response.code());
				}
			}
			JSONObject obj = new JSONObject(response.body().string());
			String roomid = obj.getString("room_id");
			roomCache.put(roomAlias, roomid);
			return roomid;
		}
	}

	private String createRoom(String roomAlias, String invitee, boolean is_direkt) throws IOException {
		JSONObject payload = new JSONObject();
		payload.put("room_alias_name", getLocalAliasPart(roomAlias));
		if (invitee != null) {
			payload.put("invite", new JSONArray().put(invitee));
		}
		payload.put("is_direct", is_direkt);
		payload.put("preset", "trusted_private_chat");
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return null;
		}

		HttpUrl url = HttpUrl
				.parse(MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + "/_matrix/client/v3/createRoom")
				.newBuilder()
				// .addQueryParameter("user_id", invitee) // creator acts as invitee
				.build();

		Request request = new Request.Builder().url(url)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue())
				.post(RequestBody.create(payload.toString(), MediaType.parse("application/json"))).build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("Room creation failed: " + response.code());
			}
			JSONObject obj = new JSONObject(response.body().string());
			String roomid = obj.getString("room_id");
			roomCache.put(roomAlias, roomid);
			return roomid;
		}
	}

	public void joinRoomIfNecessary(String roomId, String matrixUserId) throws IOException {
		String cacheKey = roomId + "|" + matrixUserId;

		// Falls bereits gejoint – abbrechen
		if (joinedRoomCache.get(cacheKey) != null) {
			return;
		}
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return;
		}

		HttpUrl url = HttpUrl
				.parse(MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + "/_matrix/client/v3/join/"
						+ URLEncoder.encode(roomId, StandardCharsets.UTF_8))
				.newBuilder().addQueryParameter("user_id", matrixUserId).build();

		Request request = new Request.Builder().url(url)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue())
				.post(RequestBody.create("", MediaType.parse("application/json"))).build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				if (response.code() == 403) {
					// 403 = vermutlich schon gejoint oder nicht erlaubt
					Log.warn("Join forbidden or already joined for " + matrixUserId + " in " + roomId);
				} else {
					throw new IOException("Join failed for " + matrixUserId + " in " + roomId + ": " + response.code());
				}
			}

			// Erfolg oder 403 ⇒ merken
			joinedRoomCache.put(cacheKey, true);
		}
	}

	private void inviteUserToRoom(String roomId, String userId, String fromUserId) throws IOException {
		JSONObject payload = new JSONObject();
		payload.put("user_id", userId);
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return;
		}

		HttpUrl url = HttpUrl
				.parse(MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + "/_matrix/client/v3/rooms/"
						+ URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/invite")
				.newBuilder().addQueryParameter("user_id", fromUserId).build();

		Request request = new Request.Builder().url(url)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue())
				.post(RequestBody.create(payload.toString(), MediaType.parse("application/json"))).build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful() && response.code() != 403) {
				throw new IOException("Invite failed: " + response.code());
			}
		}
	}

	private boolean isUserInRoom(String roomId, String userId, String actingUser) throws IOException {
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return false;
		}
		
		HttpUrl url = HttpUrl
				.parse(MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + "/_matrix/client/v3/rooms/"
						+ URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/joined_members")
				.newBuilder().addQueryParameter("user_id", actingUser).build();

		Request request = new Request.Builder().url(url)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue()).get().build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("Failed to get members: " + response.code());
			}

			JSONObject obj = new JSONObject(response.body().string());
			JSONObject members = obj.getJSONObject("joined");

			return members.has(userId);
		}
	}

	// Raum veröffentlichen
	public void publishRoom(String roomId, String roomAliasName, String avatarUrl, String name, String topic) {
		JSONObject payload = new JSONObject();
		payload.put("visibility", "public");
		payload.put("room_alias_name", roomAliasName);
		payload.put("world_readable", true);
		payload.put("guest_can_join", false);
		if (avatarUrl != null && !avatarUrl.isEmpty()) {
			payload.put("avatar_url", avatarUrl);
		}
		if (name != null) {
			payload.put("name", name);
		}
		payload.put("topic", topic != null ? topic : "not available");
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return;
		}

		String url = MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + "/_matrix/client/v3/directory/list/room/"
				+ encodeRoomId(roomId);

		Request request = new Request.Builder().url(url)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue())
				.post(RequestBody.create(payload.toString(), MediaType.parse("application/json"))).build();

		try {
			Response response = client.newCall(request).execute();
			if (!response.isSuccessful()) {
				Log.error("Failed to publish room: " + response.code() + " - " + response.message());
			}
			Log.info("Room published successfully: " + roomId);
		} catch (Exception e) {
			Log.error("Failed to publish room: " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
		}
	}

	// Raum entfernen
	public void removeRoom(String roomId) {
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return;
		}
		
		String url = MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue() + "/_matrix/client/v3/directory/list/room/"
				+ encodeRoomId(roomId);

		Request request = new Request.Builder().url(url)
				.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue()).delete().build();

		try {
			Response response = client.newCall(request).execute();
			if (!response.isSuccessful()) {
				Log.error("Failed to remove room: " + response.code() + " - " + response.message());
			}
			Log.info("Room removed successfully: " + roomId);
		} catch (Exception e) {
			Log.error("Failed to remove room: " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
		}
	}
	
	public JSONArray getPublicRooms() throws IOException, JSONException {
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return null;
		}
		
	    String url = MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()  + "/_matrix/client/v3/publicRooms?limit=50";

	    Request.Builder builder = new Request.Builder()
	        .url(url)
	        .get();

	    if (MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue() != null) {
	        builder.addHeader("Authorization", "Bearer " + MatrixBridgePlugin.MATRIX_AS_TOKEN.getValue());
	    }

	    Request request = builder.build();
	    try (Response response = client.newCall(request).execute()) {
	        if (!response.isSuccessful()) {
	            throw new IOException("Failed to get public rooms: " + response.code());
	        }
	        String body = response.body().string();
	        JSONObject json = new JSONObject(body);
	        return json.getJSONArray("chunk");  // Liste der Räume
	    }
	}

	// Hilfsmethode zum URL-encoden des Raum-IDs (wegen ! und :)
	private String encodeRoomId(String roomId) {
		return URLEncoder.encode(roomId, StandardCharsets.UTF_8);
	}
	
	public JSONObject getUserProfile(String userId) throws IOException, FileNotFoundException {
		
		if (MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue()==null||MatrixBridgePlugin.MATRIX_HOMESERVER_URL.getValue().isEmpty())
		{
			Log.error("MatrixBridgePlugin.MATRIX_HOMESERVER_URL nicht gesetzt!");
			return null;
		}

		Request request = authRequest("/_matrix/client/v3/profile/" + URLEncoder.encode(userId, StandardCharsets.UTF_8)).get().build();

		Response response = client.newCall(request).execute();
		if (!response.isSuccessful()) {
			if (response.code() == 404) {
				Log.info("User not found: " + userId);
				throw new FileNotFoundException("User not found: " + userId);
			} else {
				Log.error("Failed to get profile (" + userId + "): " + response.code());
				throw new IOException("Failed to get profile (" + userId + "): " + response.code());
			}
			
		}
		return new JSONObject(response.body().string());
	}

	public void leaveRoom(String room_id, String matrixUser) {

	}
	
	
	
	
	

	public void joinRoom(String roomIdOrAlias) throws IOException {
		Request request = authRequest("/_matrix/client/v3/join/" + roomIdOrAlias).post(RequestBody.create(new byte[0]))
				.build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("Matrix join room failed: " + response.body().string());
			}
		}
	}

	public void setPresence(String presence, String statusMsg) throws IOException {
		JSONObject json = new JSONObject();
		json.put("presence", presence);
		json.put("status_msg", statusMsg);

		Request request = authRequest("/_matrix/client/v3/presence/@bot:yourdomain/sendPresence")
				.put(RequestBody.create(json.toString(), MediaType.parse("application/json"))).build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("Matrix presence update failed: " + response.body().string());
			}
		}
	}

	public JSONObject getRoomMembers(String roomId) throws IOException {
		Request request = authRequest("/_matrix/client/v3/rooms/" + roomId + "/members").get().build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("Matrix room members fetch failed: " + response.body().string());
			}
			return new JSONObject(response.body().string());
		}
	}



}
