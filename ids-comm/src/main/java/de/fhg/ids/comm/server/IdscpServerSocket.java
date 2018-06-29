/*-
 * ========================LICENSE_START=================================
 * Camel IDS Component
 * %%
 * Copyright (C) 2017 Fraunhofer AISEC
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package de.fhg.ids.comm.server;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.camel.util.jsse.SSLContextParameters;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.InvalidProtocolBufferException;

import de.fhg.aisec.ids.api.conm.RatResult;
import de.fhg.aisec.ids.messages.Idscp.ConnectorMessage;
import de.fhg.ids.comm.ws.protocol.ProtocolMachine;
import de.fhg.ids.comm.ws.protocol.ProtocolState;
import de.fhg.ids.comm.ws.protocol.fsm.Event;
import de.fhg.ids.comm.ws.protocol.fsm.FSM;

/**
 * Handles messages for the IDS protocol.
 * 
 * Messages from and to the web socket are connected to the FSM implementing the actual protocol.
 * 
 * @author Julian Schütte (julian.schuette@aisec.fraunhofer.de)
 *
 */
@WebSocket
public class IdscpServerSocket {
    private static final Logger LOG = LoggerFactory.getLogger(IdscpServerSocket.class);
    private FSM fsm;
    private SSLContextParameters params;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition isFinishedCond = lock.newCondition();

    private ServerConfiguration config;
	private SocketListener socketListener;
	private Session session;
	
	public IdscpServerSocket(ServerConfiguration config, SocketListener socketListener) {
		// Create provider socket
		this.config = config;
		this.socketListener = socketListener;
	}
    
	/**
	 * Called upon incoming connection to server.
	 * 
	 * @param session
	 */
	@OnWebSocketConnect
    public void onOpen(Session session) {
        LOG.debug("Websocket opened " + this + " from " + session.getRemoteAddress().toString() + " to " + session.getLocalAddress().toString());

        this.session = session;

        // create Finite State Machine for IDS protocol
        ProtocolMachine machine = new ProtocolMachine();
    	fsm = machine.initIDSProviderProtocol(session, this.config.attestationType, this.config.attestationMask, this.config.tpmdSocket);    	
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        LOG.debug("websocket closed");
        fsm.reset();
        socketListener.notifyClosed(this);
    }

    @OnWebSocketError
    public void onError(Throwable t) {
        LOG.debug("websocket on error", t);
        if (fsm!=null) {
        	fsm.reset();
        }
    }

    /**
     * Handles incoming messages to server.
     * 
     * @param session
     * @param frame
     */
    @OnWebSocketFrame
    public void onMessage(Session session, Frame frame) {
    	byte[] message = new byte[frame.getPayload().remaining()];
    	frame.getPayload().get(message);
    	LOG.debug("Received in state " + fsm.getState() + ": " + new String(message));
    	try {
    		lock.lockInterruptibly();
    		try {
        		if (fsm.getState().equals(ProtocolState.IDSCP_END.id()) || fsm.getState().equals(ProtocolState.IDSCP_ERROR.id())) {
        			
        			LOG.debug("Passing through to web socket " + new String(message));
        			if (this.socketListener != null) {
        				this.socketListener.onMessage(session, message);
        			}
        			return;
        		}
    			ConnectorMessage msg = ConnectorMessage.parseFrom(message);
    			fsm.feedEvent(new Event(msg.getType(), new String(message), msg));
    		} catch (InvalidProtocolBufferException e) {
    			LOG.error(e.getMessage() + ": " + new String(message), e);
    			fsm.feedEvent(new Event(ConnectorMessage.Type.ERROR, e.getMessage(), ConnectorMessage.getDefaultInstance()));
    		}
    	} catch (InterruptedException e) {
			LOG.warn(e.getMessage());
			Thread.currentThread().interrupt();
		} finally {
			lock.unlock();
		}
    }
    
    public ReentrantLock semaphore() {
    	return lock;
    }

    public Condition isFinished() {
    	return isFinishedCond;
    }

    //get the result of the remote attestation
	public RatResult getAttestationResult() {
		return fsm.getRatResult();
	}
	
	public String getMetaData() {
		return fsm.getMetaData();
	}
	
	public Session getSession() {
		return this.session;
	}
}