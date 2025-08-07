package de.mopsdom.matrix.utils;

import java.util.List;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.muc.MUCOccupant;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import de.mopsdom.xmpp.MatrixBridgePlugin;

public class Utils {

	private static final Logger Log = LoggerFactory.getLogger(Utils.class);

	public static String convertXmppUserJIDToMatrixID(JID jid, String prefix) {
		// z. B. user_b@openfire.local → #user_b:openfire.local
		return prefix + jid.getNode() + ":" + jid.getDomain();
	}
	
	public static JID convertMatrixIdToXMPPJid(String matrixid) {
		 	String localPart;
		    String domain;

		    if (matrixid.contains(":")) {
		        String[] parts = matrixid.split(":", 2);
		        localPart = parts[0];
		        domain = parts[1];
		    } else {
		        localPart = matrixid;
		        domain = MatrixBridgePlugin.MATRIX_COMPONENT_NAME.getValue()+"."+XMPPServer.getInstance().getServerInfo().getXMPPDomain();
		    }

		    // Entferne führende # oder !
		    if (localPart.startsWith("#") || localPart.startsWith("!")) {
		        localPart = localPart.substring(1);
		    }

		    return new JID(localPart + "@" + domain );
	}

	public static JID getJidFromMucJid(JID jid) {
		MultiUserChatManager manager = XMPPServer.getInstance().getMultiUserChatManager();
		MultiUserChatService service = manager.getMultiUserChatService(jid);
		if (service == null)
			return null;

		MUCRoom room = service.getChatRoom(jid.getNode());
		if (room == null)
			return null;

		try {
			List<MUCOccupant> occupants = room.getOccupantsByNickname(jid.getDomain());
			if (occupants.size() > 0) {
				return occupants.get(0).getUserAddress();
			}
			Log.error("User not found with nickname from jid: " + jid.toString());
			return null;

		} catch (UserNotFoundException e) {
			Log.error("User not found with nickname from jid: " + jid.toString());
			return null;
		}
	}
}
