package client.hub;

import static data.ProtocolManager.FOOTER;
import static data.ProtocolManager.Q_AC;
import static data.ProtocolManager.Q_MC;
import static data.ProtocolManager.Q_RP;
import static data.ProtocolManager.Q_RS;
import static data.ProtocolManager.R_AC;
import static data.ProtocolManager.R_MC;
import static data.ProtocolManager.R_RP;
import static data.ProtocolManager.R_RS;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import main.Utils;
import client.ClientApp;
import client.communicator.CommController;
import client.communicator.ServerCallable;
import data.ClientMapWay;
import data.LatLongPoint;
import data.ParseException;
import data.ProtocolManager;

/**
 * @author dgattey
 */
public class HubController implements Controllable {
	
	private static final LatLongPoint	APP_LOAD_POINT	= new LatLongPoint(41.827196, -71.400369);
	
	private final ClientApp				app;
	private final String				hostName;
	private final int					serverPort;
	private boolean						connected;
	private final UUID					hubID;
	private Thread						trafficThread;
	private final Map<String, Double>	trafficMap;
	
	/**
	 * Main Constructor for HubController
	 * 
	 * @param hostName the host name to use as the server's hostname
	 * @param serverPort the server port to use when connecting to the server
	 * @param app the app that's running this hub controller
	 * @throws IOException if there was an error communicating with the server
	 */
	public HubController(final String hostName, final int serverPort, final ClientApp app) throws IOException {
		this.hostName = hostName;
		this.serverPort = serverPort;
		this.app = app;
		trafficMap = new HashMap<>();
		hubID = UUID.randomUUID();
		
		// Constant thread checking connection - will also update the label
		new Timer().scheduleAtFixedRate(new TimerTask() {
			
			@Override
			public void run() {
				updateConnection();
			}
			
		}, new Date(), 1800);
	}
	
	/**
	 * Checks the connected status and updates the view appropriately
	 */
	protected void updateConnection() {
		final boolean previous = connected;
		connected = CommController.checkConnection(hostName, serverPort);
		app.getViewController().setConnectionLabel(connected);
		if (!previous && connected || app.getViewController().getChunks().isEmpty()) {
			app.getViewController().chunk();
			restartTrafficLoop();
		}
	}
	
	/**
	 * Updates traffic data in client by starting a new loop of traffic getting whenever necessary
	 */
	private void restartTrafficLoop() {
		if (trafficThread != null) {
			trafficThread.interrupt();
		}
		trafficThread = new Thread() {
			
			@Override
			public void run() {
				try {
					if (Thread.interrupted()) {
						return;
					}
					final CommController trafficC = new CommController(hostName, serverPort);
					trafficC.connect();
					trafficC.writeWithNL(ProtocolManager.Q_TR);
					final BufferedReader reader = trafficC.getReader();
					String line;
					while ((line = reader.readLine()) != null && !Thread.interrupted()) {
						final Entry<String, Double> trafficData = ProtocolManager.parseTrafficData(line);
						if (trafficData == null) {
							return;
						}
						trafficMap.put(trafficData.getKey(), trafficData.getValue());
						app.getViewController().repaintMap();
					}
					trafficC.disconnect();
				} catch (final IOException e) {
					return; // Shouldn't matter since it'll be restarted when the connection returns
				}
			};
		};
		trafficThread.start();
	}
	
	/**
	 * Returns a value for a street name from the traffic map - returns null if nothing found for that street
	 * 
	 * @param street a possible name of a street for traffic data
	 * @return a value for the street's traffic
	 */
	public Double getTrafficValue(final String street) {
		return trafficMap.get(street);
	}
	
	/**
	 * Checks whether this hub is connected
	 * 
	 * @return a boolean representing connection status to server
	 */
	public boolean isConnected() {
		return connected;
	}
	
	/**
	 * Gets the public load point of the app (used as center)
	 * 
	 * @return the center point of the app
	 */
	public LatLongPoint getAppLoadPoint() {
		return APP_LOAD_POINT;
	}
	
	@Override
	public List<ClientMapWay> getRoute(final LatLongPoint a, final LatLongPoint b) {
		if (a == null || b == null) {
			throw new IllegalArgumentException("<HubController> null points to route find not allowed");
		}
		List<ClientMapWay> ret = null;
		if (isConnected()) {
			try {
				ret = CommController.getFromServer(new ServerCallable<List<ClientMapWay>>(hostName, serverPort) {
					
					@Override
					protected List<ClientMapWay> writeAndGetInfo(final CommController comm) throws IOException,
							ParseException {
						comm.writeWithNL(Q_RP + hubID.toString());
						comm.write(a);
						comm.write(b);
						comm.writeWithNL(FOOTER);
						
						final BufferedReader reader = comm.getReader();
						comm.checkForResponseHeader(R_RP);
						final List<ClientMapWay> ret = ProtocolManager.parseWayList(reader);
						comm.checkForResponseFooter();
						return ret;
					}
				});
			} catch (final IOException | ParseException e) {
				handleError(e);
			}
		}
		if (ret == null) {
			throw new IllegalArgumentException("<HubController> route was null");
		}
		return ret;
	}
	
	@Override
	public List<ClientMapWay> getRoute(final String streetA1, final String streetA2, final String streetB1,
			final String streetB2) {
		
		// Error checking
		if (streetA1 == null || streetA1.isEmpty() || streetA2 == null || streetA2.isEmpty() || streetB1 == null
			|| streetB1.isEmpty() || streetB2 == null || streetB2.isEmpty()) {
			throw new IllegalArgumentException("<HubController> empty or null streets to route find not allowed");
		}
		
		// TODO: throw illegal exception for no intersections to display (from backend)
		List<ClientMapWay> ret = null;
		if (isConnected()) {
			try {
				ret = CommController.getFromServer(new ServerCallable<List<ClientMapWay>>(hostName, serverPort) {
					
					@Override
					protected List<ClientMapWay> writeAndGetInfo(final CommController comm) throws IOException,
							ParseException {
						comm.writeWithNL(Q_RS + hubID.toString());
						comm.writeWithNL(streetA1);
						comm.writeWithNL(streetA2);
						comm.writeWithNL(streetB1);
						comm.writeWithNL(streetB2);
						comm.writeWithNL(FOOTER);
						
						final BufferedReader reader = comm.getReader();
						comm.checkForResponseHeader(R_RS);
						final List<ClientMapWay> ret = ProtocolManager.parseWayList(reader);
						comm.checkForResponseFooter();
						return ret;
					}
				});
			} catch (final IOException | ParseException e) {
				handleError(e);
			}
		}
		if (ret == null) {
			throw new IllegalArgumentException("<HubController> route was null");
		}
		return ret;
		
	}
	
	@Override
	public List<String> getSuggestions(final String input) {
		List<String> ret = null;
		if (isConnected()) {
			try {
				ret = CommController.getFromServer(new ServerCallable<List<String>>(hostName, serverPort) {
					
					@Override
					protected List<String> writeAndGetInfo(final CommController comm) throws IOException,
							ParseException {
						comm.writeWithNL(Q_AC);
						comm.writeWithNL(input);
						comm.writeWithNL(FOOTER);
						
						final BufferedReader reader = comm.getReader();
						comm.checkForResponseHeader(R_AC);
						final List<String> ret = ProtocolManager.parseStreetList(reader);
						comm.checkForResponseFooter();
						return ret;
					}
					
				});
			} catch (final IOException | ParseException e) {
				handleError(e);
			}
		}
		return ret;
	}
	
	@Override
	public List<ClientMapWay> getChunk(final LatLongPoint min, final LatLongPoint max) {
		List<ClientMapWay> ret = null;
		if (isConnected()) {
			try {
				ret = CommController.getFromServer(new ServerCallable<List<ClientMapWay>>(hostName, serverPort) {
					
					@Override
					protected List<ClientMapWay> writeAndGetInfo(final CommController comm) throws IOException,
							ParseException {
						comm.writeWithNL(Q_MC);
						comm.write(min);
						comm.write(max);
						comm.writeWithNL(FOOTER);
						
						final BufferedReader reader = comm.getReader();
						comm.checkForResponseHeader(R_MC);
						final List<ClientMapWay> ret = ProtocolManager.parseWayList(reader);
						comm.checkForResponseFooter();
						return ret;
					}
					
				});
			} catch (final IOException | ParseException e) {
				handleError(e);
			}
		}
		return ret;
	}
	
	/**
	 * Prints an error out that happened in communicating with server
	 * 
	 * @param e an exception
	 */
	private static void handleError(final Exception e) {
		Utils.printError("<HubController> Communication failed: " + e.getMessage());
	}
}
