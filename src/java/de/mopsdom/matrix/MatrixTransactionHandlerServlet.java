package de.mopsdom.matrix;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.mopsdom.matrix.utils.HttpUtils;
import de.mopsdom.xmpp.MatrixBridgePlugin;
import de.mopsdom.xmpp.XmppAPI;

public class MatrixTransactionHandlerServlet extends HttpServlet {

	private static final long serialVersionUID = -5390787218532362247L;
	private static final Logger Log = LoggerFactory.getLogger(MatrixTransactionHandlerServlet.class);

	private XmppAPI xmppApi = null;
	
	public MatrixTransactionHandlerServlet() {
		super();
		xmppApi = XmppAPI.getInstance();
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {

		String authorization = req.getHeader("Authorization") != null
				? req.getHeader("Authorization").replace("Bearer", "").trim()
				: null;

		if (authorization == null || !authorization.equals(MatrixBridgePlugin.MATRIX_HS_TOKEN.getValue())) {
			JSONObject m_forbidden = new JSONObject();
			m_forbidden.put("errcode", "M_FORBIDDEN");
			m_forbidden.put("error", "Application service is not allowed to perform this action");
			HttpUtils.sendResult(resp, 403, m_forbidden);
			Log.error("Got Packet from Maxtrix without or invalid HS_TOKEN");
			return;
		}

		try {
			String path = req.getRequestURI();
			
			// Ping Endpoint
			if (path.equals("/_matrix/app/v1/ping")) {
				xmppApi.handlePing(req, resp);
				return;
			}
			
			// Transactions Endpunkte
			if (path.matches("/_matrix/app/v1/transactions/[^/]+") || path.matches("/transactions/[^/]+")) {
			    Pattern pattern = Pattern.compile(".*/transactions/([^/]+)");
			    Matcher matcher = pattern.matcher(req.getRequestURI());

			    String txnId = null;
			    if (matcher.matches()) {
			        txnId = matcher.group(1);
			    }

			    if (txnId != null) {
			        xmppApi.handleTransactions(req, resp, txnId);
			    } else {
			    	JSONObject m_forbidden = new JSONObject();
					m_forbidden.put("errcode", "M_BAD_REQUEST");
					m_forbidden.put("error", "Missing transaction ID");
					HttpUtils.sendResult(resp, HttpServletResponse.SC_BAD_REQUEST, m_forbidden);
					Log.error("Got Packet from Maxtrix without transaction ID");
			    }
			    return;
			}

			// Users Endpunkte
			if (path.matches("/_matrix/app/v1/users/[^/]+") || path.matches("/users/[^/]+")) {
				xmppApi.checkUserIDIsExistingOnOpenfire(req, resp);
				return;
			}

			// Rooms Endpunkte
			if (path.matches("/_matrix/app/v1/rooms/[^/]+") || path.matches("/rooms/[^/]+")) {
				xmppApi.checkRoomAliasExists(req, resp);
				return;
			}

			// Thirdparty Protocol Endpunkte
			if (path.matches("/_matrix/app/v1/thirdparty/protocol/[^/]+")
					|| path.matches("/_matrix/app/unstable/thirdparty/protocol/[^/]+")) {
				xmppApi.handleThirdpartyProtocol(req, resp);
				return;
			}

			// Thirdparty User Endpunkte
			if (path.matches("/_matrix/app/v1/thirdparty/user/[^/]+")
					|| path.matches("/_matrix/app/unstable/thirdparty/user/[^/]+")) {
				xmppApi.handleThirdpartyUser(req, resp);
				return;
			}

			// Thirdparty Location Endpunkte
			if (path.matches("/_matrix/app/v1/thirdparty/location/[^/]+")
					|| path.matches("/_matrix/app/unstable/thirdparty/location/[^/]+")) {
				xmppApi.handleThirdpartyLocation(req, resp);
				return;
			}

			// Thirdparty User List Endpunkt (ohne ID)
			if (path.equals("/_matrix/app/v1/thirdparty/user")
					|| path.equals("/_matrix/app/unstable/thirdparty/user")) {
				xmppApi.handleThirdpartyUserList(req, resp);
				return;
			}

			// Thirdparty Location List Endpunkt (ohne ID)
			if (path.equals("/_matrix/app/v1/thirdparty/location")
					|| path.equals("/_matrix/app/unstable/thirdparty/location")) {
				xmppApi.handleThirdpartyLocationList(req, resp);
				return;
			}

			// Wenn Pfad unbekannt, 404 M_UNRECOGNIZED
			JSONObject err = new JSONObject();
			err.put("errcode", "M_UNRECOGNIZED");
			err.put("error", "Unknown endpoint");
			HttpUtils.sendResult(resp, 404, err);
			Log.error("Got Packet from Maxtrix with unknown endpoint declaration");

		} catch (Exception e) {
			JSONObject err = new JSONObject();
			err.put("errcode", "M_UNKNOWN");
			err.put("error", "Internal server error: " + (e.getMessage()!=null?e.getMessage():"unknown error"));
			HttpUtils.sendResult(resp, 500, err);
			Log.error("Fatal Error while receiving packet from Matrix: "+(e.getMessage()!=null?e.getMessage():"unknown error"));
		}
	}
}
