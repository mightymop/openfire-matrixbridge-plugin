package de.mopsdom.xmpp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;

import org.dom4j.Element;
import org.eclipse.jetty.ee8.webapp.WebAppContext;
import org.jivesoftware.admin.AuthCheckFilter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.container.PluginMetadataHelper;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.jivesoftware.util.SystemProperty;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.Component;
import org.xmpp.component.ComponentException;
import org.xmpp.component.ComponentManager;
import org.xmpp.component.ComponentManagerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

import de.mopsdom.matrix.MatrixAPI;
import de.mopsdom.matrix.utils.Utils;

public class MatrixBridgePlugin implements Plugin, Component, PropertyEventListener {

	private static final Logger Log = LoggerFactory.getLogger(MatrixBridgePlugin.class);

	public static final SystemProperty<Boolean> HTTP_IGNORE_SSL = SystemProperty.Builder.ofType(Boolean.class)
			.setKey("plugin.matrix_bridge.ignore_ssl").setPlugin("matrix_bridge").setDefaultValue(false)
			.setDynamic(true).build();

	public static final SystemProperty<String> MATRIX_HS_TOKEN = SystemProperty.Builder.ofType(String.class)
			.setKey("plugin.matrix_bridge.hs_token").setPlugin("matrix_bridge").setDefaultValue(null).setDynamic(true)
			.build();

	public static final SystemProperty<String> MATRIX_AS_TOKEN = SystemProperty.Builder.ofType(String.class)
			.setKey("plugin.matrix_bridge.as_token").setPlugin("matrix_bridge").setDefaultValue(null).setDynamic(true)
			.build();

	public static final SystemProperty<String> MATRIX_HOMESERVER_URL = SystemProperty.Builder.ofType(String.class)
			.setKey("plugin.matrix_bridge.homeserver_url").setPlugin("matrix_bridge").setDefaultValue(null)
			.setDynamic(true).build();

	public static final SystemProperty<String> MATRIX_COMPONENT_NAME = SystemProperty.Builder.ofType(String.class)
			.setKey("plugin.matrix_bridge.component_name").setPlugin("matrix_bridge").setDefaultValue("matrix")
			.setDynamic(false).build();

	private WebAppContext contextPage = null;

	private ComponentManager componentManager;

	private MatrixAPI matrixApi;

	private final String[] publicResources = new String[] { "/matrix/*" };

	public void initializePlugin(PluginManager manager, File pluginDirectory) {

		Log.info("Starte Matrix Bridge Plugin");

		matrixApi = MatrixAPI.getInstance();
		componentManager = ComponentManagerFactory.getComponentManager();
		try {
			String name = MATRIX_COMPONENT_NAME.getValue() != null ? MATRIX_COMPONENT_NAME.getValue()
					: MATRIX_COMPONENT_NAME.getDefaultValue();
			componentManager.addComponent(name, this);
			for (String publicResource : this.publicResources)
				AuthCheckFilter.addExclude(publicResource);

			contextPage = new WebAppContext(null, pluginDirectory.getPath() + File.separator + "classes", "/matrix");
			contextPage.setClassLoader(this.getClass().getClassLoader());
			HttpBindManager.getInstance().addJettyHandler(contextPage);
		} catch (ComponentException e) {
			Log.error(e.getMessage(), e);
		}
	}

	public void destroyPlugin() {

		Log.info("Beende Matrix Bridge Plugin");

		if (componentManager != null) {
			try {
				componentManager.removeComponent(MATRIX_COMPONENT_NAME.getValue());
			} catch (Exception e) {
				Log.error(e.getMessage(), e);
			}
		}
		componentManager = null;

		if (this.contextPage != null) {
			HttpBindManager.getInstance().removeJettyHandler(this.contextPage);
			this.contextPage.destroy();
			this.contextPage = null;
		}
		for (String publicResource : this.publicResources)
			AuthCheckFilter.removeExclude(publicResource);

		SystemProperty.removePropertiesForPlugin("matrix_bridge");
		PropertyEventDispatcher.removeListener(this);
	}

	@Override
	public void propertySet(String property, Map<String, Object> params) {

	}

	@Override
	public void propertyDeleted(String property, Map<String, Object> params) {
		// TODO Auto-generated method stub

	}

	@Override
	public void xmlPropertySet(String property, Map<String, Object> params) {
		// TODO Auto-generated method stub

	}

	@Override
	public void xmlPropertyDeleted(String property, Map<String, Object> params) {
		// TODO Auto-generated method stub
	}

	@Override
	public String getName() {
		return PluginMetadataHelper.getName(this);
	}

	@Override
	public String getDescription() {
		return PluginMetadataHelper.getDescription(this);
	}

	@Override
	public void processPacket(Packet packet) {
		if (packet instanceof IQ) {
			// Handle disco packets
			IQ iq = (IQ) packet;

			if (iq.getType() == IQ.Type.result) {
				handleIQResult(iq);
			} else if (iq.getType() == IQ.Type.error) {
				handleIQError(iq);
			} else if (iq.getType() == IQ.Type.get) {
				handleIQGet(iq);
			} else if (iq.getType() == IQ.Type.set) {
				handleIQSet(iq);
			}
		} else if (packet instanceof Message) {

			handleMessage((Message) packet);
		} else if (packet instanceof Presence) {

			handlePresence((Presence) packet);
		}
	}

	@Override
	public void initialize(JID jid, ComponentManager componentManager) throws ComponentException {
		// TODO Auto-generated method stub
	}

	@Override
	public void start() {
		// TODO Auto-generated method stub

	}

	@Override
	public void shutdown() {
		// TODO Auto-generated method stub

	}

	protected void handleIQResult(IQ iq) {
		Log.debug(iq.toString());
	}

	protected void handleIQError(IQ iq) {
		Log.debug(iq.toString());
	}

	protected void handleIQGet(IQ iq) {
		Log.debug(iq.toString());

		Element query = iq.getChildElement();
		if (query != null) {
			String namespace = query.getNamespaceURI();

			if ("http://jabber.org/protocol/disco#info".equals(namespace)&&iq.getTo().toString().equals(getComponentDomain())) {
				sendDiscoInfoResult(iq);
			}
			else
			if ("http://jabber.org/protocol/disco#info".equals(namespace)) {
				sendDiscoInfoResultFromMatrixUser(iq);
			}
			else
			if ("http://jabber.org/protocol/disco#items".equals(namespace)&&iq.getTo().toString().equals(getComponentDomain())) {
				sendDiscoItemsResult(iq);
			}
			else
			if ("jabber:iq:version".equals(namespace)) {
				sendVersionResponse(iq);
			}
			else {
				sendIQError(iq,-1, PacketError.Condition.feature_not_implemented.toString());
			}
		}
		else
		{
			sendIQError(iq, 400, PacketError.Condition.bad_request.toString());
		}
	}
	
	private String getComponentDomain() {
		return MATRIX_COMPONENT_NAME.getValue()+"."+XMPPServer.getInstance().getServerInfo().getXMPPDomain();
	}

	protected void sendDiscoItemsResult(IQ iq) {
		IQ result = IQ.createResultIQ(iq);
		Element query = result.setChildElement("query", "http://jabber.org/protocol/disco#items");

		try {
			JSONArray publicRooms = matrixApi.getPublicRooms();

			if (publicRooms!=null)
			{
				for (int i = 0; i < publicRooms.length(); i++) {
					JSONObject room = publicRooms.getJSONObject(i);
	
					// Matrix room_id, z.B. "!abcdefg:matrix.org"
					String roomId = room.optString("room_id");
					String name = room.optString("name", "Unnamed Room");
	
					Element item = query.addElement("item");
					item.addAttribute("jid", Utils.convertMatrixIdToXMPPJid(roomId).toString()); 
					item.addAttribute("name", name);
					
				}
			}
			
			
			if (true)
			{
				String roomId = "!abcdefg12345:matrix.org";
				String name = "Example Room";

				Element item = query.addElement("item");
				item.addAttribute("jid", Utils.convertMatrixIdToXMPPJid(roomId).toString()); 
				item.addAttribute("name", name);
			}
			
		} catch (IOException | JSONException e) {
			Log.error("Fehler beim Abrufen öffentlicher Räume vom Matrixserver: " + e.getMessage());
		}

		sendIQResult(result);
	}

	private void sendIQResult(IQ result) {
		try {
			componentManager.sendPacket(this, result);
		} catch (ComponentException e) {
			Log.error("Konnte ein IQ Packet nicht senden: " + e.getMessage());
			Log.error(result.toXML());
		}
	}
	
	private void sendIQError(IQ src, int code, String errortagname) {
		IQ result = IQ.createResultIQ(src);
		Element error = result.setChildElement("error",null);
		error.addAttribute("type","cancel");
		
		if (code>=0)
			error.addAttribute("code",String.valueOf(code));
		
		error.addElement(errortagname, "urn:ietf:params:xml:ns:xmpp-stanzas");
		
		try {
			componentManager.sendPacket(this, result);
		} catch (ComponentException e) {
			Log.error("Konnte ein IQ Packet nicht senden: " + e.getMessage());
			Log.error(result.toXML());
		}
	}

	
	private void sendVersionResponse(IQ iq) {
		IQ result = IQ.createResultIQ(iq);
		Element query = result.setChildElement("query", "jabber:iq:version");

		query.addElement("name").setText(PluginMetadataHelper.getName(this));
		query.addElement("version").setText(PluginMetadataHelper.getVersion(this).getVersionString());
		query.addElement("os").setText(System.getProperty("os.name") + " / Java " + System.getProperty("java.version"));

		sendIQResult(result);
	}

	private void sendDiscoInfoResultFromMatrixUser(IQ iq)
	{
		Element query = iq.getChildElement();
	    if (query != null && "http://jabber.org/protocol/disco#info".equals(query.getNamespaceURI())) {
	        JID to = iq.getTo();
	        IQ result = IQ.createResultIQ(iq);
	        
	        if (to != null) {
	            String matrixId = Utils.convertXmppUserJIDToMatrixID(to, "@xmpp_"); // z. B. test@matrix.mopstation → @xmpp_test:matrix.org
	            JSONObject profile=null;
				try {
					profile = matrixApi.getUserProfile(matrixId);
					
				    Element queryRes = result.setChildElement("query", "http://jabber.org/protocol/disco#info");

				    queryRes.addElement("identity")
				        .addAttribute("category", "client")
				        .addAttribute("type", "user")
				        .addAttribute("name", to.getNode());

				    queryRes.addElement("feature").addAttribute("var", "http://jabber.org/protocol/disco#info");
				    queryRes.addElement("feature").addAttribute("var", "jabber:iq:version");

				    try {
				        componentManager.sendPacket(this, result);
				    } catch (ComponentException e) {
				        Log.error("Fehler beim Senden von disco#info Antwort: " + e.getMessage());
				    }
				    
				}
				catch (FileNotFoundException ex)
				{
					sendIQError(iq, 404, PacketError.Condition.item_not_found.toString());
				}
				catch (IOException e) {
					sendIQError(iq, 501, PacketError.Condition.internal_server_error.toString());
				}
				
	            if (profile!=null) {
	            	sendIQResult(result);
	            } else {
	            	sendIQError(iq,404, PacketError.Condition.item_not_found.toString());
	            	return;
	            }
	        }
	    }
	    
	    sendIQError(iq, 400, PacketError.Condition.bad_request.toString());
	}
	
	private void sendDiscoInfoResult(IQ iq) {
		IQ result = IQ.createResultIQ(iq);
		Element query = result.setChildElement("query", "http://jabber.org/protocol/disco#info");

		Element identity1 = query.addElement("identity");
		identity1.addAttribute("category", "conference");
		identity1.addAttribute("type", "text");
		identity1.addAttribute("name", PluginMetadataHelper.getName(this));
		
		Element identity2 = query.addElement("identity");
		identity2.addAttribute("category", "directory");
		identity2.addAttribute("type", "chatroom");
		identity2.addAttribute("name", PluginMetadataHelper.getName(this));
		
		Element identity3 = query.addElement("identity");
		identity3.addAttribute("category", "gateway");
		identity3.addAttribute("type", "matrix");
		identity3.addAttribute("name", PluginMetadataHelper.getName(this));

		// Standard-Features für eine Matrix-Bridge
		addFeature(query, "jabber:iq:gateway");
		addFeature(query, "http://jabber.org/protocol/disco#info");
		addFeature(query, "http://jabber.org/protocol/disco#items");
		addFeature(query, "urn:xmpp:ping");
		addFeature(query, "urn:xmpp:receipts");
		addFeature(query, "http://jabber.org/protocol/muc");

		// Optional: Eigener Namespace zur Identifikation
		addFeature(query, "urn:matrix:bridge:1");

		sendIQResult(result);
	}

	private void addFeature(Element query, String var) {
		Element feature = query.addElement("feature");
		feature.addAttribute("var", var);
	}

	protected void handleIQSet(IQ iq) {
		Log.debug("handleIQSet(): " + iq.toString());

		// Prüfe ob IQ MUC-Konfiguration enthält
		if (iq.getChildElement() != null
				&& "http://jabber.org/protocol/muc#owner".equals(iq.getChildElement().getNamespaceURI())) {

			JID to = iq.getTo(); // z. B. afuwu@conference.mopstation

			// Handle MUC destroy
			if (isRoomDestroy(iq)) {
				Log.info("Detected MUC destruction: " + to.toString());

				String roomAlias = "#" + to.getNode() + ":" + to.getDomain(); // z. B. #afuwu:conference.mopstation

				try {
					String roomId = matrixApi.resolveRoomAlias(roomAlias);
					if (roomId != null) {
						matrixApi.removeRoom(roomId);
						Log.info("Matrix-Raum aus Verzeichnis entfernt: " + roomAlias);
					}
				} catch (Exception e) {
					Log.warn("Fehler beim Entfernen des Matrix-Raums: " + e.getMessage(), e);
				}
			}
		}
	}

	protected void handleMessage(Message message) {
		Log.debug("handleMessage(): " + message.toString());
		matrixApi.sendMessageToUser(message);
	}

	protected void handlePresence(Presence presence) {
		Log.debug("handlePresence(): " + presence.toString());

		JID toJid = presence.getTo();
		if (toJid == null)
			return;

		// Prüfen ob Presence in einen MUC-Raum mit Nick (also join)
		if (isMucRoomJID(toJid)) {
			// Prüfen, ob <x xmlns='http://jabber.org/protocol/muc'> vorhanden
			Element xElement = presence.getElement().element("x");
			if (xElement != null && "http://jabber.org/protocol/muc".equals(xElement.getNamespaceURI())) {

				String roomAlias = "#" + toJid.getNode() + ":" + toJid.getDomain().split("/")[0];
				JID userjid = Utils.getJidFromMucJid(toJid);

				matrixApi.publishRoom(roomAlias, roomAlias, null, null, null);
				if (userjid != null) {
					String matrixUser = Utils.convertXmppUserJIDToMatrixID(userjid, "@xmpp_");
					try {
						matrixApi.joinRoomIfNecessary(roomAlias, matrixUser);
					} catch (Exception e) {
						Log.error("Fehler bei der Abarbeitung einer Presence: " + e.getMessage());
					}
				}
			}

			if ("unavailable".equals(presence.getType())) {
				Element destroy = xElement.element("destroy");
				if (destroy != null) {
					// Raum wurde zerstört – optionales Entfernen aus dem Matrix-Verzeichnis
					try {
						String roomAlias = "#" + toJid.getNode() + ":" + toJid.getDomain();
						String roomId = matrixApi.resolveRoomAlias(roomAlias);
						JID userjid = Utils.getJidFromMucJid(toJid);
						String matrixUser = Utils.convertXmppUserJIDToMatrixID(userjid, "@xmpp_");

						matrixApi.leaveRoom(roomId, matrixUser);

					} catch (Exception e) {
						Log.warn("Fehler beim Verlassen eines Matrix-Raums: " + e.getMessage(), e);
					}
				}

				// Optional: Leave-Logik oder Cleanup hier
				Log.info("User hat MUC-Raum verlassen: " + toJid.toString());
			}
		}
	}

	private boolean isRoomDestroy(IQ iq) {
		Element query = iq.getChildElement();
		if (query != null) {
			Element destroy = query.element("destroy");
			return destroy != null;
		}
		return false;
	}

	private boolean isMucRoomMessage(Message msg) {
		return msg.getType() == Message.Type.groupchat;
	}

	private boolean isMucRoomJID(JID jid) {
		MultiUserChatManager manager = XMPPServer.getInstance().getMultiUserChatManager();

		String bare = jid.getDomain().split("/")[0];
		String subdomain = bare.replace("." + XMPPServer.getInstance().getServerInfo().getXMPPDomain(), "");
		if (manager.getMultiUserChatService(subdomain) != null) {
			if (manager.getMultiUserChatService(subdomain).getChatRoom(jid.getNode()) != null) {
				return true;
			}
		}

		return false;
	}
}
