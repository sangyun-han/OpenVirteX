/*******************************************************************************
 * Copyright (c) 2013 Open Networking Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 ******************************************************************************/

package net.onrc.openvirtex.elements.datapath;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.onrc.openvirtex.core.io.OVXSendMsg;
import net.onrc.openvirtex.elements.link.PhysicalLink;
import net.onrc.openvirtex.elements.port.OVXPort;
import net.onrc.openvirtex.exceptions.IndexOutOfBoundException;
import net.onrc.openvirtex.exceptions.RoutingAlgorithmException;
import net.onrc.openvirtex.messages.Devirtualizable;
import net.onrc.openvirtex.routing.RoutingAlgorithms;
import net.onrc.openvirtex.routing.RoutingAlgorithms.RoutingType;
import net.onrc.openvirtex.routing.SwitchRoute;
import net.onrc.openvirtex.util.BitSetIndex;
import net.onrc.openvirtex.util.BitSetIndex.IndexType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openflow.protocol.OFMessage;

/**
 * The Class OVXBigSwitch.
 * 
 */

public class OVXBigSwitch extends OVXSwitch {

    private static Logger                                         log = LogManager
	                                                                      .getLogger(OVXBigSwitch.class
	                                                                              .getName());

    private RoutingAlgorithms                                     alg;
    
    private final BitSetIndex                                     routeCounter;

    /** The calculated routes */
    private final HashMap<OVXPort, HashMap<OVXPort, SwitchRoute>> routeMap;

    public OVXBigSwitch(final long switchId, final int tenantId) {
	super(switchId, tenantId);
	try {this.alg = new RoutingAlgorithms("spf", (byte)1);
        } catch (RoutingAlgorithmException e) {log.error("Routing algorithm not set for big-switch " + this.getSwitchName());}
	this.routeMap = new HashMap<OVXPort, HashMap<OVXPort, SwitchRoute>>();
	this.routeCounter = new BitSetIndex(IndexType.ROUTE_ID);
    }

    /**
     * Gets the alg.
     * 
     * @return the alg
     */
    public RoutingAlgorithms getAlg() {
	return this.alg;
    }

    public void setAlg(final RoutingAlgorithms alg) {
	this.alg = alg;
    }

    /**
     * @param srcPort
     *            the ingress port on the Big Switch
     * @param dstPort
     *            the egress port on the Big Switch
     * @return The route
     */
    public SwitchRoute getRoute(final OVXPort srcPort, final OVXPort dstPort) {
	return this.alg.getRoutable().getRoute(this, srcPort, dstPort);
    }

    /**
     * Fetch all routes associated with a specific port, assuming that the
     * routes are duplex. Does not account for the use of backups for now...
     * 
     * @param port
     * @return
     */
    public Set<SwitchRoute> getRoutebyPort(final OVXPort port) {
	final Set<SwitchRoute> routes = new HashSet<SwitchRoute>();
	for (final OVXPort vport : this.portMap.values()) {
	    if (vport.equals(port)) {
		continue;
	    }
	    final SwitchRoute rt = this.getRoute(port, vport);
	    if (rt != null) {
		final SwitchRoute revrt = this.getRoute(vport, port);
		routes.add(rt);
		routes.add(revrt);
	    }
	}
	return routes;
    }

    /**
     * Fetch a bi-directional route based on the routeId
     * 
     * @param routeId
     * @return Set<SwitchRoute>
     */
    public Set<SwitchRoute> getRoutebyId(final Integer routeId) {
	final Set<SwitchRoute> routes = new HashSet<SwitchRoute>();
	for (HashMap<OVXPort, SwitchRoute> portMap : this.routeMap.values()) {
	    for (SwitchRoute route : portMap.values()) {
		if (route.getRouteId() == routeId.intValue())
		    routes.add(route);
	    }
	}
	return routes;
    }
    
    public HashMap<OVXPort, HashMap<OVXPort, SwitchRoute>> getRouteMap() {
	return this.routeMap;
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.onrc.openvirtex.elements.datapath.Switch#removePort(short)
     */
    @Override
    public boolean removePort(final Short portNumber) {
	if (!this.portMap.containsKey(portNumber)) {
	    return false;
	} else {
	    this.portMap.remove(portNumber);
	    return true;
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * net.onrc.openvirtex.core.io.OVXSendMsg#sendMsg(org.openflow.protocol.
     * OFMessage, net.onrc.openvirtex.core.io.OVXSendMsg)
     */
    @Override
    public void sendMsg(final OFMessage msg, final OVXSendMsg from) {
	// TODO Truncate the message for the ctrl to the missSetLenght value
	if (this.isConnected && this.isActive) {
	    this.channel.write(Collections.singletonList(msg));
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * net.onrc.openvirtex.elements.datapath.Switch#handleIO(org.openflow.protocol
     * .OFMessage)
     */
    @Override
    public void handleIO(final OFMessage msg) {
	try {
	    ((Devirtualizable) msg).devirtualize(this);
	} catch (final ClassCastException e) {
	    OVXBigSwitch.log.error("Received illegal message : " + msg);
	}
    }

    @Override
    public boolean boot() {
	/**
	 * If the bigSwitch internal routing mechanism is not manual,
	 * pre-compute all the path
	 * between switch ports during the boot. The path is computed only if
	 * the ovxPorts belong
	 * to different physical switches.
	 */
	if (this.alg.getRoutingType() != RoutingType.NONE) {
	    for (final OVXPort srcPort : this.portMap.values()) {
		for (final OVXPort dstPort : this.portMap.values()) {
		    if (srcPort.getPortNumber() != dstPort.getPortNumber()
			    && srcPort.getPhysicalPort().getParentSwitch() != dstPort
			            .getPhysicalPort().getParentSwitch()) {
			this.getRoute(srcPort, dstPort);
		    }
		}
	    }
	}
	return super.boot();
    }

    public boolean unregisterRoute(final Integer routeId) {
	for (HashMap<OVXPort, SwitchRoute> portMap : this.routeMap.values()) {
	    for (SwitchRoute route : portMap.values()) {
		if (route.getRouteId() == routeId.intValue()) {
		    this.routeCounter.releaseIndex(routeId);
		    this.map.removeRoute(route);
		    return this.routeMap.get(route.getSrcPort()).remove(route.getDstPort()) != null;
		}
	    }
	}
	return false;
    }

    @Override
    public void unregister() {
	for (HashMap<OVXPort, SwitchRoute> portMap : this.routeMap.values()) {
	    for (final SwitchRoute route : portMap.values()) {
		this.map.removeRoute(route);
	    }
	    super.unregister();
	}
    }

    /**
     * Tries to gracefully disable the parts of this BVS that map to the
     * specified PhysicalSwitch. This method returns true if the shutdown of the
     * PhysicalSwitch does not compromise this switch's functions e.g. a
     * backup path can be found through the switch.
     * 
     * @param phySwitch
     * @return true for success, false otherwise.
     */
    public boolean tryRecovery(final PhysicalSwitch phySwitch) {
	// TODO actually do recovery.
	return false;
    }

    /**
     * Gets the port.
     * 
     * @param portNumber
     *            the port number
     * @return the port instance
     */
    @Override
    public OVXPort getPort(final Short portNumber) {
	return this.portMap.get(portNumber);
    };

    @Override
    public String toString() {
	return "SWITCH:\n- switchId: " + this.switchId + "\n- switchName: "
	        + this.switchName + "\n- isConnected: " + this.isConnected
	        + "\n- tenantId: " + this.tenantId + "\n- missSendLenght: "
	        + this.missSendLen + "\n- isActive: " + this.isActive
	        + "\n- capabilities: "
	        + this.capabilities.getOVXSwitchCapabilities();
    }

    @Override
    public void sendSouth(final OFMessage msg, final OVXPort inPort) {
	if (inPort == null) {
	    /* TODO for some OFTypes, we can recover an inport. */
	    return;
	}
	final PhysicalSwitch sw = inPort.getPhysicalPort().getParentSwitch();
	log.debug("Sending packet to sw {}: {}", sw.getName(), msg);
	sw.sendMsg(msg, this);
    }

    public void sendSouthBS(final OFMessage msg, final OVXPort inPort) {
	final PhysicalSwitch sw = inPort.getPhysicalPort().getParentSwitch();
	sw.sendMsg(msg, this);
    }

    /**
     * Adds a path between two edge ports on the big switch
     * 
     * @param ingress
     * @param egress
     * @param path
     *            list of links
     * @param revpath
     *            the corresponding reverse path from egress to ingress
     * @param priority 
     * @return the route ID of the new route
     * @throws IndexOutOfBoundException
     */
    public SwitchRoute createRoute(final OVXPort ingress, final OVXPort egress,
	    final List<PhysicalLink> path, final List<PhysicalLink> revpath, byte priority)
	    throws IndexOutOfBoundException {
	final int routeId = this.routeCounter.getNewIndex();
	final SwitchRoute rtEntry = new SwitchRoute(ingress, egress,
	        this.switchId, routeId, this.tenantId, priority);
	final SwitchRoute revRtEntry = new SwitchRoute(egress, ingress,
	        this.switchId, routeId, this.tenantId, priority);
	this.map.addRoute(rtEntry, path);
	this.map.addRoute(revRtEntry, revpath);

	this.addToRouteMap(ingress, egress, rtEntry);
	this.addToRouteMap(egress, ingress, revRtEntry);

	OVXBigSwitch.log
	        .info("Added route for big-switch {} between ports ({},{}) with path: {}",
	                this.switchName, ingress.getPortNumber(),
	                egress.getPortNumber(), path.toString());
	OVXBigSwitch.log
	        .info("Added route for big-switch {} between ports ({},{}) with path: {}",
	                this.switchName, egress.getPortNumber(),
	                ingress.getPortNumber(), revpath.toString());
	return rtEntry;
    }

    private void addToRouteMap(final OVXPort in, final OVXPort out,
	    final SwitchRoute entry) {
	HashMap<OVXPort, SwitchRoute> rtmap = this.routeMap.get(in);
	if (rtmap == null) {
	    rtmap = new HashMap<OVXPort, SwitchRoute>();
	    this.routeMap.put(in, rtmap);
	}
	rtmap.put(out, entry);
    }

    @Override
    public int translate(final OFMessage ofm, final OVXPort inPort) {
	if (inPort == null) {
	    // don't know the PhysicalSwitch, for now return original XID.
	    return ofm.getXid();
	} else {
	    // we know the PhysicalSwitch
	    final PhysicalSwitch psw = inPort.getPhysicalPort()
		    .getParentSwitch();
	    return psw.translate(ofm, this);
	}
    }
}
