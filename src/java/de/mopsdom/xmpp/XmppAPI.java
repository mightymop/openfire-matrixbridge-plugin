package de.mopsdom.xmpp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dom4j.Element;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.vcard.VCardManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.mopsdom.matrix.utils.HttpUtils;

public class XmppAPI {
	private static final Logger Log = LoggerFactory.getLogger(XmppAPI.class);
	
    private static XmppAPI instance=null;
    private XMPPServer openfireServerInstance=null;
    	
    private final Map<String, Boolean> transactionCache = Collections.synchronizedMap(
    	    new LinkedHashMap<>() {
    	        @Override
    	        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
    	            return this.size() > 1000;
    	        }
    	    }
    	);
    
    public boolean isAlreadyProcessed(String txnId) {
        synchronized (transactionCache) {
            if (transactionCache.containsKey(txnId)) return true;
            transactionCache.put(txnId, Boolean.TRUE);
            return false;
        }
    }

	public XmppAPI() {
		instance = this; 
		openfireServerInstance = XMPPServer.getInstance();
	}
	
	public static XmppAPI getInstance() {
		if (instance==null)
			instance = new XmppAPI();
		
		return instance;
	}
	
	public void handleTransactions(HttpServletRequest req, HttpServletResponse resp, String txnId) {

		if (isAlreadyProcessed(txnId))
		{
			HttpUtils.sendResult(resp,200,new JSONObject());
			return;
		}
		
		String body;
		try {
			body = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))
			        .lines().collect(Collectors.joining("\n"));
		} catch (IOException e) {
			JSONObject err = new JSONObject();
			err.put("errcode", "M_UNKNOWN");
			err.put("error", "Internal server error: " + (e.getMessage()!=null?e.getMessage():"unknown error"));
			HttpUtils.sendResult(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err);
			Log.error("Fatal Error while receiving packet from Matrix: "+(e.getMessage()!=null?e.getMessage():"unknown error"));
			return;
		}

	    if (body == null || body.isEmpty()) {
	        JSONObject err = new JSONObject()
	                .put("errcode", "M_BAD_JSON")
	                .put("error", "Empty body");
	        HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
	        return;
	    }

	    JSONObject transaction;
	    try {
	        transaction = new JSONObject(body);
	    } catch (JSONException e) {
	        JSONObject err = new JSONObject()
	                .put("errcode", "M_BAD_JSON")
	                .put("error", "Invalid JSON: " + e.getMessage());
	        HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
	        return;
	    }

	    JSONArray events = transaction.optJSONArray("events");
	    if (events == null) {
	        JSONObject err = new JSONObject()
	                .put("errcode", "M_BAD_JSON")
	                .put("error", "Missing 'events' array");
	        HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
	        return;
	    }

	    for (int i = 0; i < events.length(); i++) {
	        JSONObject event = events.getJSONObject(i);
	        processMatrixEvent(event);  // eigene Logik zur Verarbeitung
	    }

	    
	    HttpUtils.sendResult(resp,HttpServletResponse.SC_OK,new JSONObject());
	}
	
	private void processMatrixEvent(JSONObject event)
	{
		Log.debug("processMatrixEvent()");
		Log.debug(event.toString());
	}
	
	public void handlePing(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		// Body lesen
		String body = new BufferedReader(new InputStreamReader(req.getInputStream()))
				.lines().collect(Collectors.joining("\n"));

		JSONObject json;
		try {
			json = new JSONObject(body);
		} catch (Exception e) {
			JSONObject err = new JSONObject()
					.put("errcode", "M_BAD_JSON")
					.put("error", "Malformed JSON: " + e.getMessage());
			HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
			return;
		}

		String txnId = json.optString("transaction_id", null);

		if (txnId == null || txnId.isEmpty()) {
			JSONObject err = new JSONObject()
					.put("errcode", "M_BAD_REQUEST")
					.put("error", "Missing transaction_id");
			HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
			return;
		}

		Log.info("Received ping with transaction_id: " + txnId);
		// Erfolgreich: leeres JSON-Objekt zurückgeben
		HttpUtils.sendResult(resp,HttpServletResponse.SC_OK,new JSONObject());
	}
	
	public void checkRoomAliasExists(HttpServletRequest req, HttpServletResponse resp) throws IOException {
	    String path = req.getRequestURI(); // z.B. /_matrix/app/v1/rooms/#roomalias:domain
	    Pattern pattern = Pattern.compile(".*/rooms/(#[^:]+):(.+)");
	    Matcher matcher = pattern.matcher(path);

	    if (!matcher.matches()) {
	        JSONObject err = new JSONObject()
	            .put("errcode", "M_BAD_REQUEST")
	            .put("error", "Invalid room alias format");
	        HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
	        return;
	    }

	    String aliasLocalpart = matcher.group(1); // z.B. "#roomalias"
	    String domain = matcher.group(2);         // z.B. "yourdomain"

	    // Prüfen, ob Domain zur eigenen Instanz passt
	    String openfireDomain = openfireServerInstance.getServerInfo().getXMPPDomain();

	    // Prüfen, ob die Domain mit der Openfire-Domain endet
	    if (!domain.endsWith(openfireDomain)) {
	        JSONObject err = new JSONObject()
	            .put("errcode", "M_NOT_FOUND")
	            .put("error", "Room alias not found");
	        HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
	        return;
	    }
	    
	    // Subdomain vor der Openfire-Domain extrahieren (MUC Service Domain)
		String mucServiceDomain = domain.substring(0, domain.length() - openfireDomain.length() - 1);
	    if (mucServiceDomain == null || mucServiceDomain.trim().isEmpty()) {
	        JSONObject err = new JSONObject()
	            .put("errcode", "M_NOT_FOUND")
	            .put("error", "Room alias not found");
	        HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
	        return;
	    }


	    try {
	        // Room Aliases sind in Openfire z.B. als MultiUserChat Services (MUC)
	        // Du musst prüfen, ob ein Raum mit diesem Alias existiert
	    	MultiUserChatManager mucManager = openfireServerInstance.getMultiUserChatManager();
	        MultiUserChatService mucService = mucManager.getMultiUserChatService(mucServiceDomain);
	        
	        if (mucService == null) {
	            JSONObject err = new JSONObject()
	                .put("errcode", "M_NOT_FOUND")
	                .put("error", "Room alias not found");
	            HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
	            return;
	        }
	        
	        String roomName = aliasLocalpart.startsWith("#") ? aliasLocalpart.substring(1) : aliasLocalpart;
	        
	        MUCRoom room = mucService.getChatRoom(roomName);

	        if (room != null) {
	            JSONObject result = new JSONObject();
	            result.put("room_id", room.getJID().toString());
	            HttpUtils.sendResult(resp, HttpServletResponse.SC_OK, result);
	        } else {
	            JSONObject err = new JSONObject()
	                .put("errcode", "M_NOT_FOUND")
	                .put("error", "Room alias not found");
	            HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
	        }
	    } catch (Exception e) {
	        JSONObject err = new JSONObject()
	            .put("errcode", "M_UNKNOWN")
	            .put("error", "Internal error: " + e.getMessage());
	        HttpUtils.sendResult(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err);
	    }
	}

	public void checkUserIDIsExistingOnOpenfire(HttpServletRequest req, HttpServletResponse resp) throws IOException {
	    String path = req.getRequestURI(); // z.B. /_matrix/app/v1/users/@user:domain
	    Pattern pattern = Pattern.compile(".*/users/@([^:]+):(.+)");
	    Matcher matcher = pattern.matcher(path);

	    if (!matcher.matches()) {
	    	JSONObject err = new JSONObject()
					.put("errcode", "M_BAD_REQUEST")
					.put("error", "Invalid userId format");
			HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
	        return;
	    }

	    String localpart = matcher.group(1); // "user"
	    String domain = matcher.group(2);    // "yourdomain"
	    
	    if (openfireServerInstance.getServerInfo().getXMPPDomain().equalsIgnoreCase(domain))	    	
	    {
	    	try 
	    	{
	    		if (openfireServerInstance.getUserManager().getUser(localpart)!=null)
	    		{	    		
	    			HttpUtils.sendResult(resp, HttpServletResponse.SC_OK, new JSONObject());
	    		}
	    		else	    			
	    		{
	    			JSONObject err = new JSONObject()
	    					.put("errcode", "M_NOT_FOUND")
	    					.put("error", "User not found");
	    			HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
	    		}
	    	}
	    	catch (Exception e)
	    	{
	    		JSONObject err = new JSONObject()
						.put("errcode", "M_BAD_REQUEST")
						.put("error", e.getMessage());
				HttpUtils.sendResult(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err);
	    	}
	    }
	    else
	    {
	    	JSONObject err = new JSONObject()
					.put("errcode", "M_NOT_FOUND")
					.put("error", "User not found");
			HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
	    }
	}
	
	public void handleThirdpartyProtocol(HttpServletRequest req, HttpServletResponse resp) {
		String path = req.getRequestURI();
        String[] parts = path.split("/");
        if (parts.length < 6) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_BAD_REQUEST")
					.put("error", "Protocol not specified");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
            return;
        }
        String protocol = parts[5]; // z.B. "xmpp"
        if (!"xmpp".equalsIgnoreCase(protocol)) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_NOT_FOUND")
					.put("error", "Protocol not found");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
            return;
        }

        // Rückgabe laut Matrix Spec (vereinfachtes Beispiel)
        JSONObject result = new JSONObject();
        result.put("protocol", "xmpp");

        JSONArray fields = new JSONArray();
        // Beispiel-Felder (kann erweitert werden)
        fields.put(new JSONObject()
            .put("key", "user")
            .put("name", "User ID")
            .put("type", "m.text")
            .put("required", true)
            .put("description", "The local part of an XMPP user, e.g. 'alice'"));

        result.put("fields", fields);

        // "location_fields" könnten z.B. Raum-Alias sein
        JSONArray locationFields = new JSONArray();
        locationFields.put(new JSONObject()
            .put("key", "room")
            .put("name", "Room alias")
            .put("type", "m.text")
            .put("required", false)
            .put("description", "The alias of an XMPP MUC room"));
        result.put("location_fields", locationFields);

        HttpUtils.sendResult(resp, HttpServletResponse.SC_OK, result);
	}
	
	public void handleThirdpartyUser(HttpServletRequest req, HttpServletResponse resp) {
		// z.B. /_matrix/app/v1/thirdparty/user/@alice:openfire.local
        String path = req.getRequestURI();
        // Extrahiere User-ID
        String[] parts = path.split("/");
        if (parts.length < 6) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_BAD_REQUEST")
					.put("error", "User ID not specified");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
            return;
        }
        String userId = parts[5]; // z.B. "@alice:openfire.local"

        // Parst userId nach localpart und domain
        if (!userId.startsWith("@") || !userId.contains(":")) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_BAD_REQUEST")
					.put("error", "Invalid user ID format");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
            return;
        }

        String localpart = userId.substring(1, userId.indexOf(":"));
        String domain = userId.substring(userId.indexOf(":") + 1);

        if (!openfireServerInstance.getServerInfo().getXMPPDomain().equalsIgnoreCase(domain)) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_NOT_FOUND")
					.put("error", "User not found");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
            return;
        }

        UserManager userManager = openfireServerInstance.getUserManager();
        User user = null;
        try {
        	user=userManager.getUser(localpart);
        }
        catch (Exception e)
        {
        	JSONObject err = new JSONObject();
			err.put("errcode", "M_UNKNOWN");
			err.put("error", "Internal server error: " + (e.getMessage()!=null?e.getMessage():"unknown error"));
			HttpUtils.sendResult(resp, 500, err);
			Log.error("Fatal Error while search the user: "+(e.getMessage()!=null?e.getMessage():"unknown error"));
			return;
        }

        if (user == null) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_NOT_FOUND")
					.put("error", "User not found");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
            return;
        }

        JSONObject fields = new JSONObject();
        fields.put("displayname", user.getName());

        try {
            VCardManager vCardManager = openfireServerInstance.getVCardManager();
            Element vcard = vCardManager.getVCard(user.getUsername());

            if (vcard != null) {
                // E-Mail
                Element emailEl = vcard.element("EMAIL");
                if (emailEl != null) {
                    Element useridEl = emailEl.element("USERID");
                    if (useridEl != null) {
                        fields.put("email", useridEl.getTextTrim());
                    }
                }

                // Nickname
                Element nicknameEl = vcard.element("NICKNAME");
                if (nicknameEl != null) {
                    fields.put("nickname", nicknameEl.getTextTrim());
                }

                // Telefonnummer
                Element telEl = vcard.element("TEL");
                if (telEl != null) {
                    Element numberEl = telEl.element("NUMBER");
                    if (numberEl != null) {
                        fields.put("phone", numberEl.getTextTrim());
                    }
                }

                // Avatar (PHOTO als base64)
                Element photoEl = vcard.element("PHOTO");
                if (photoEl != null) {
                    Element binvalEl = photoEl.element("BINVAL");
                    Element typeEl = photoEl.element("TYPE");

                    if (binvalEl != null && typeEl != null) {
                        String mimeType = typeEl.getTextTrim();
                        String base64 = binvalEl.getTextTrim();

                        // Optional: Als Data-URL
                        String avatarUrl = "data:" + mimeType + ";base64," + base64;
                        fields.put("avatar_url", avatarUrl);
                    }
                }
                else
                {
                	fields.put("avatar_url", JSONObject.NULL); // fallback
                }
            }
        } catch (Exception e) {
            // Optional: Logging
            fields.put("avatar_url", JSONObject.NULL); // fallback
        }

        JSONObject result = new JSONObject();
        result.put("protocol", "xmpp");
        result.put("userid", userId);
        result.put("fields", fields);

        HttpUtils.sendResult(resp, HttpServletResponse.SC_OK, result);
	}
	
	public void handleThirdpartyLocation(HttpServletRequest req, HttpServletResponse resp) {
		// z.B. /_matrix/app/v1/thirdparty/location/#room:openfire.local
        String path = req.getRequestURI();
        String[] parts = path.split("/");
        if (parts.length < 6) {
        	JSONObject err = new JSONObject()
					.put("errcode", "SC_BAD_REQUEST")
					.put("error", "Location not specified");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
            return;
        }
        String locationId = parts[5]; // z.B. "#room:openfire.local"

        if (!locationId.contains(":") || !locationId.startsWith("#")) {
        	JSONObject err = new JSONObject()
					.put("errcode", "SC_BAD_REQUEST")
					.put("error", "Invalid location format");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, err);
            return;
        }

        String alias = locationId.substring(0, locationId.indexOf(":")); // #room
        String domain = locationId.substring(locationId.indexOf(":") + 1); // openfire.local

        if (!openfireServerInstance.getServerInfo().getXMPPDomain().equalsIgnoreCase(domain)) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_NOT_FOUND")
					.put("error", "Location not found");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
            return;
        }

        MultiUserChatManager mucManager = openfireServerInstance.getMultiUserChatManager();
        MultiUserChatService mucService = mucManager.getMultiUserChatService("conference"); // MUC Service fest

        if (mucService == null) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_NOT_FOUND")
					.put("error", "Location not found");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
            return;
        }

        MUCRoom room = mucService.getChatRoom(alias);

        if (room == null) {
        	JSONObject err = new JSONObject()
					.put("errcode", "M_NOT_FOUND")
					.put("error", "Location not found");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
            return;
        }

        // Antwort: z.B. Raum-ID, Name, Beschreibung
        JSONObject result = new JSONObject();
        result.put("protocol", "xmpp");
        result.put("roomid", room.getJID().toString());
        result.put("fields", new JSONObject()
            .put("name", room.getName())
            .put("topic", room.getDescription())
            .put("alias", alias + ":" + domain)
        );

        HttpUtils.sendResult(resp, HttpServletResponse.SC_OK, result);
	}
	
	public void handleThirdpartyUserList(HttpServletRequest req, HttpServletResponse resp) {
		UserManager userManager = openfireServerInstance.getUserManager();
        Collection<User> users = userManager.getUsers();

        JSONArray usersArray = new JSONArray();

        for (User user : users) {
            JSONObject u = new JSONObject();
            String userId = "@" + user.getUsername() + ":" + openfireServerInstance.getServerInfo().getXMPPDomain();
            u.put("userid", userId);
            u.put("protocol", "xmpp");
            usersArray.put(u);
        }

        JSONObject result = new JSONObject();
        result.put("users", usersArray);

        HttpUtils.sendResult(resp, HttpServletResponse.SC_OK, result);
	}
	
	public void handleThirdpartyLocationList(HttpServletRequest req, HttpServletResponse resp) {
		MultiUserChatManager mucManager = openfireServerInstance.getMultiUserChatManager();
        MultiUserChatService mucService = mucManager.getMultiUserChatService("conference"); // fester Service-Name

        if (mucService == null) {
            JSONObject err = new JSONObject()
					.put("errcode", "M_NOT_FOUND")
					.put("error", "No MUC service");
        	HttpUtils.sendResult(resp, HttpServletResponse.SC_NOT_FOUND, err);
            return;
        }

        Collection<MUCRoom> rooms = mucService.getActiveChatRooms();

        JSONArray locationsArray = new JSONArray();

        String domain = openfireServerInstance.getServerInfo().getXMPPDomain();

        for (MUCRoom room : rooms) {
            JSONObject r = new JSONObject();
            // room alias als #alias:domain
            String alias = "#" + room.getName() + ":" + domain;
            r.put("alias", alias);
            r.put("protocol", "xmpp");
            locationsArray.put(r);
        }

        JSONObject result = new JSONObject();
        result.put("locations", locationsArray);

        HttpUtils.sendResult(resp, HttpServletResponse.SC_OK, result);
	}
	
}
