/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.client.impl;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.Executor;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.MessageHandler;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.protocol.core.Channel;
import org.hornetq.core.protocol.core.impl.wireformat.SessionConsumerCloseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionConsumerFlowCreditMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionQueueQueryResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionReceiveContinuationMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionReceiveLargeMessage;
import org.hornetq.utils.Future;
import org.hornetq.utils.PriorityLinkedList;
import org.hornetq.utils.PriorityLinkedListImpl;
import org.hornetq.utils.TokenBucketLimiter;

/**
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:ovidiu@feodorov.com">Ovidiu Feodorov</a>
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 * @author <a href="mailto:ataylor@redhat.com">Andy Taylor</a>
 * @version <tt>$Revision: 3603 $</tt> $Id: ClientConsumerImpl.java 3603 2008-01-21 18:49:20Z timfox $
 */
public class ClientConsumerImpl implements ClientConsumerInternal
{
   // Constants
   // ------------------------------------------------------------------------------------

   private static final Logger log = Logger.getLogger(ClientConsumerImpl.class);
   
   private static final boolean isTrace = log.isTraceEnabled();

   private static final boolean trace = ClientConsumerImpl.log.isTraceEnabled();

   public static final long CLOSE_TIMEOUT_MILLISECONDS = 10000;

   public static final int NUM_PRIORITIES = 10;

   public static final SimpleString FORCED_DELIVERY_MESSAGE = new SimpleString("_hornetq.forced.delivery.seq");

   // Attributes
   // -----------------------------------------------------------------------------------

   private final ClientSessionInternal session;

   private final Channel channel;

   private final long id;

   private final SimpleString filterString;

   private final SimpleString queueName;

   private final boolean browseOnly;

   private final Executor sessionExecutor;

   private final int clientWindowSize;

   private final int ackBatchSize;

   private final PriorityLinkedList<ClientMessageInternal> buffer = new PriorityLinkedListImpl<ClientMessageInternal>(ClientConsumerImpl.NUM_PRIORITIES);

   private final Runner runner = new Runner();

   private LargeMessageControllerImpl currentLargeMessageController;

   // When receiving LargeMessages, the user may choose to not read the body, on this case we need to discard the body
   // before moving to the next message.
   private ClientMessageInternal largeMessageReceived;

   private final TokenBucketLimiter rateLimiter;

   private volatile Thread receiverThread;

   private volatile Thread onMessageThread;

   private volatile MessageHandler handler;

   private volatile boolean closing;

   private volatile boolean closed;

   private volatile int creditsToSend;

   private volatile Exception lastException;

   private volatile int ackBytes;

   private volatile ClientMessageInternal lastAckedMessage;

   private boolean stopped = false;

   private long forceDeliveryCount;

   private final SessionQueueQueryResponseMessage queueInfo;

   private volatile boolean ackIndividually;

   // Constructors
   // ---------------------------------------------------------------------------------

   public ClientConsumerImpl(final ClientSessionInternal session,
                             final long id,
                             final SimpleString queueName,
                             final SimpleString filterString,
                             final boolean browseOnly,
                             final int clientWindowSize,
                             final int ackBatchSize,
                             final TokenBucketLimiter rateLimiter,
                             final Executor executor,
                             final Channel channel,
                             final SessionQueueQueryResponseMessage queueInfo)
   {
      this.id = id;

      this.queueName = queueName;

      this.filterString = filterString;

      this.browseOnly = browseOnly;

      this.channel = channel;

      this.session = session;

      this.rateLimiter = rateLimiter;

      sessionExecutor = executor;

      this.clientWindowSize = clientWindowSize;

      this.ackBatchSize = ackBatchSize;

      this.queueInfo = queueInfo;
   }

   // ClientConsumer implementation
   // -----------------------------------------------------------------

   private ClientMessage receive(long timeout, final boolean forcingDelivery) throws HornetQException
   {
      checkClosed();

      if (largeMessageReceived != null)
      {
         // Check if there are pending packets to be received
         largeMessageReceived.discardBody();
         largeMessageReceived = null;
      }

      if (rateLimiter != null)
      {
         rateLimiter.limit();
      }

      if (handler != null)
      {
         throw new HornetQException(HornetQException.ILLEGAL_STATE,
                                    "Cannot call receive(...) - a MessageHandler is set");
      }

      if (clientWindowSize == 0)
      {
         startSlowConsumer();
      }

      receiverThread = Thread.currentThread();

      if (timeout == 0)
      {
         // Effectively infinite
         timeout = Long.MAX_VALUE;
      }

      boolean deliveryForced = false;

      long start = -1;

      long toWait = timeout;

      try
      {
         while (true)
         {
            ClientMessageInternal m = null;

            synchronized (this)
            {
               while ((stopped || (m = buffer.poll()) == null) && !closed && toWait > 0)
               {
                  if (start == -1)
                  {
                     start = System.currentTimeMillis();
                  }

                  if (m == null && forcingDelivery)
                  {
                     if (stopped)
                     {
                        break;
                     }

                     // we only force delivery once per call to receive
                     if (!deliveryForced)
                     {
                        if (isTrace)
                        {
                           log.trace("Forcing delivery");
                        }
                        session.forceDelivery(id, forceDeliveryCount++);

                        deliveryForced = true;
                     }
                  }

                  try
                  {
                     wait(toWait);
                  }
                  catch (InterruptedException e)
                  {
                  }

                  if (m != null || closed)
                  {
                     break;
                  }

                  long now = System.currentTimeMillis();

                  toWait -= now - start;

                  start = now;
               }
            }

            if (m != null)
            {
               session.workDone();

               if (m.containsProperty(ClientConsumerImpl.FORCED_DELIVERY_MESSAGE))
               {
                  long seq = m.getLongProperty(ClientConsumerImpl.FORCED_DELIVERY_MESSAGE);

                  // Need to check if forceDelivery was called at this call
                  // As we could be receiving a message that came from a previous call
                  if (forcingDelivery && deliveryForced && seq == forceDeliveryCount - 1)
                  {
                     // forced delivery messages are discarded, nothing has been delivered by the queue
                     resetIfSlowConsumer();
                     
                     if (isTrace)
                     {
                        log.trace("There was nothing on the queue, leaving it now:: returning null");
                     }

                     return null;
                  }
                  else
                  {
                     if (isTrace)
                     {
                        log.trace("Ignored force delivery answer as it belonged to another call");
                     }
                     // Ignore the message
                     continue;
                  }
               }
               // if we have already pre acked we cant expire
               boolean expired = m.isExpired();

               flowControlBeforeConsumption(m);

               if (expired)
               {
                  m.discardBody();

                  session.expire(id, m.getMessageID());

                  if (clientWindowSize == 0)
                  {
                     startSlowConsumer();
                  }

                  if (toWait > 0)
                  {
                     continue;
                  }
                  else
                  {
                     return null;
                  }
               }

               if (m.isLargeMessage())
               {
                  largeMessageReceived = m;
               }
               
               if (isTrace)
               {
                  log.trace("Returning " + m);
               }

               return m;
            }
            else
            {
               if (isTrace)
               {
                  log.trace("Returning null");
               }
               resetIfSlowConsumer();
               return null;
            }
         }
      }
      finally
      {
         receiverThread = null;
      }
   }

   public ClientMessage receive(final long timeout) throws HornetQException
   {
      if (isBrowseOnly())
      {
         ClientMessage msg = receive(timeout, false);
         if (msg == null)
         {
            msg = receive(0, true);
         }
         return msg;
      }
      else
      {
         return receive(timeout, false);
      }
   }

   public ClientMessage receive() throws HornetQException
   {
      return receive(0, false);
   }

   public ClientMessage receiveImmediate() throws HornetQException
   {
      return receive(0, true);
   }

   public MessageHandler getMessageHandler() throws HornetQException
   {
      checkClosed();

      return handler;
   }

   // Must be synchronized since messages may be arriving while handler is being set and might otherwise end
   // up not queueing enough executors - so messages get stranded
   public synchronized void setMessageHandler(final MessageHandler theHandler) throws HornetQException
   {
      checkClosed();

      if (receiverThread != null)
      {
         throw new HornetQException(HornetQException.ILLEGAL_STATE,
                                    "Cannot set MessageHandler - consumer is in receive(...)");
      }

      boolean noPreviousHandler = handler == null;

      if (handler != theHandler && clientWindowSize == 0)
      {
         startSlowConsumer();
      }

      handler = theHandler;

      // if no previous handler existed queue up messages for delivery
      if (handler != null && noPreviousHandler)
      {
         requeueExecutors();
      }
      // if unsetting a previous handler may be in onMessage so wait for completion
      else if (handler == null && !noPreviousHandler)
      {
         waitForOnMessageToComplete(true);
      }
   }

   public void close() throws HornetQException
   {
      doCleanUp(true);
   }

   public void cleanUp()
   {
      try
      {
         doCleanUp(false);
      }
      catch (HornetQException e)
      {
         ClientConsumerImpl.log.warn("problem cleaning up: " + this);
      }
   }

   public boolean isClosed()
   {
      return closed;
   }

   public void stop() throws HornetQException
   {
      stop(true);
   }

   public void stop(final boolean waitForOnMessage) throws HornetQException
   {
      waitForOnMessageToComplete(waitForOnMessage);

      synchronized (this)
      {
         if (stopped)
         {
            return;
         }

         stopped = true;
      }
   }

   public void clearAtFailover()
   {
      clearBuffer();

      lastAckedMessage = null;

      creditsToSend = 0;

      ackIndividually = false;
   }

   public synchronized void start()
   {
      stopped = false;

      requeueExecutors();
   }

   public Exception getLastException()
   {
      return lastException;
   }

   // ClientConsumerInternal implementation
   // --------------------------------------------------------------

   public ClientSessionInternal getSession()
   {
      return session;
   }
   
   public SessionQueueQueryResponseMessage getQueueInfo()
   {
      return queueInfo;
   }

   public long getID()
   {
      return id;
   }

   public SimpleString getFilterString()
   {
      return filterString;
   }

   public SimpleString getQueueName()
   {
      return queueName;
   }

   public boolean isBrowseOnly()
   {
      return browseOnly;
   }

   public synchronized void handleMessage(final ClientMessageInternal message) throws Exception
   {
      if (closing)
      {
         // This is ok - we just ignore the message
         return;
      }

      ClientMessageInternal messageToHandle = message;

      if (messageToHandle.getAddress() == null)
      {
         messageToHandle.setAddressTransient(queueInfo.getAddress());
      }

      messageToHandle.onReceipt(this);

      if (message.getPriority() != 4)
      {
         // We have messages of different priorities so we need to ack them individually since the order
         // of them in the ServerConsumerImpl delivery list might not be the same as the order they are
         // consumed in, which means that acking all up to won't work
         ackIndividually = true;
      }

      // Add it to the buffer
      buffer.addTail(messageToHandle, messageToHandle.getPriority());

      if (handler != null)
      {
         // Execute using executor
         if (!stopped)
         {
            queueExecutor();
         }
      }
      else
      {
         notify();
      }
   }

   public synchronized void handleLargeMessage(final SessionReceiveLargeMessage packet) throws Exception
   {
      if (closing)
      {
         // This is ok - we just ignore the message
         return;
      }

      // Flow control for the first packet, we will have others

      flowControl(packet.getPacketSize(), false);

      ClientLargeMessageInternal currentChunkMessage = (ClientLargeMessageInternal)packet.getLargeMessage();

      currentChunkMessage.setDeliveryCount(packet.getDeliveryCount());

      File largeMessageCache = null;

      if (session.isCacheLargeMessageClient())
      {
         largeMessageCache = File.createTempFile("tmp-large-message-" + currentChunkMessage.getMessageID() + "-",
                                                 ".tmp");
         largeMessageCache.deleteOnExit();
      }

      currentLargeMessageController = new LargeMessageControllerImpl(this, packet.getLargeMessageSize(), 60, largeMessageCache);

      if (currentChunkMessage.isCompressed())
      {
         currentChunkMessage.setLargeMessageController(new CompressedLargeMessageControllerImpl(currentLargeMessageController));
      }
      else
      {
         currentChunkMessage.setLargeMessageController(currentLargeMessageController);
      }

      currentChunkMessage.setFlowControlSize(0);

      handleMessage(currentChunkMessage);
   }

   public synchronized void handleLargeMessageContinuation(final SessionReceiveContinuationMessage chunk) throws Exception
   {
      if (closing)
      {
         return;
      }
      currentLargeMessageController.addPacket(chunk);
   }

   public void clear(boolean waitForOnMessage) throws HornetQException
   {
      synchronized (this)
      {
         // Need to send credits for the messages in the buffer

         Iterator<ClientMessageInternal> iter = buffer.iterator();

         while (iter.hasNext())
         {
            ClientMessageInternal message = iter.next();

            flowControlBeforeConsumption(message);
         }

         clearBuffer();
      }

      // Need to send credits for the messages in the buffer

      waitForOnMessageToComplete(waitForOnMessage);
   }

   public int getClientWindowSize()
   {
      return clientWindowSize;
   }

   public int getBufferSize()
   {
      return buffer.size();
   }

   public void acknowledge(final ClientMessage message) throws HornetQException
   {
      ClientMessageInternal cmi = (ClientMessageInternal)message;

      if (ackIndividually)
      {
         if (lastAckedMessage != null)
         {
            flushAcks();
         }

         session.individualAcknowledge(id, message.getMessageID());
      }
      else
      {
         ackBytes += message.getEncodeSize();

         if (ackBytes >= ackBatchSize)
         {
            doAck(cmi);
         }
         else
         {
            lastAckedMessage = cmi;
         }
      }
   }

   public void flushAcks() throws HornetQException
   {
      if (lastAckedMessage != null)
      {
         doAck(lastAckedMessage);
      }
   }

   /** 
    * 
    * LargeMessageBuffer will call flowcontrol here, while other handleMessage will also be calling flowControl.
    * So, this operation needs to be atomic.
    * 
    * @param discountSlowConsumer When dealing with slowConsumers, we need to discount one credit that was pre-sent when the first receive was called. For largeMessage that is only done at the latest packet
    */
   public void flowControl(final int messageBytes, final boolean discountSlowConsumer) throws HornetQException
   {
      if (clientWindowSize >= 0)
      {
         creditsToSend += messageBytes;

         if (creditsToSend >= clientWindowSize)
         {
            if (clientWindowSize == 0 && discountSlowConsumer)
            {
               if (ClientConsumerImpl.trace)
               {
                  ClientConsumerImpl.log.trace("Sending " + creditsToSend + " -1, for slow consumer");
               }

               // sending the credits - 1 initially send to fire the slow consumer, or the slow consumer would be
               // always buffering one after received the first message
               final int credits = creditsToSend - 1;

               creditsToSend = 0;

               if (credits > 0)
               {
                  sendCredits(credits);
               }
            }
            else
            {
               if (ClientConsumerImpl.trace)
               {
                  ClientConsumerImpl.log.trace("Sending " + messageBytes + " from flow-control");
               }

               final int credits = creditsToSend;

               creditsToSend = 0;

               if (credits > 0)
               {
                  sendCredits(credits);
               }
            }
         }
      }
   }

   // Public
   // ---------------------------------------------------------------------------------------

   // Package protected
   // ---------------------------------------------------------------------------------------

   // Protected
   // ---------------------------------------------------------------------------------------

   // Private
   // ---------------------------------------------------------------------------------------

   /** 
    * Sending a initial credit for slow consumers
    * */
   private void startSlowConsumer()
   {
      if (ClientConsumerImpl.trace)
      {
         ClientConsumerImpl.log.trace("Sending 1 credit to start delivering of one message to slow consumer");
      }
      sendCredits(1);
   }

   private void resetIfSlowConsumer()
   {
      if (clientWindowSize == 0)
      {
         sendCredits(0);
      }
   }

   private void requeueExecutors()
   {
      for (int i = 0; i < buffer.size(); i++)
      {
         queueExecutor();
      }
   }

   private void queueExecutor()
   {
      if (ClientConsumerImpl.trace)
      {
         ClientConsumerImpl.log.trace("Adding Runner on Executor for delivery");
      }
      
      sessionExecutor.execute(runner);
   }

   /**
    * @param credits
    */
   private void sendCredits(final int credits)
   {
      channel.send(new SessionConsumerFlowCreditMessage(id, credits));
   }

   private void waitForOnMessageToComplete(boolean waitForOnMessage)
   {
      if (handler == null)
      {
         return;
      }

      if (!waitForOnMessage || Thread.currentThread() == onMessageThread)
      {
         // If called from inside onMessage then return immediately - otherwise would block
         return;
      }

      org.hornetq.utils.Future future = new Future();

      sessionExecutor.execute(future);

      boolean ok = future.await(ClientConsumerImpl.CLOSE_TIMEOUT_MILLISECONDS);

      if (!ok)
      {
         ClientConsumerImpl.log.warn("Timed out waiting for handler to complete processing");
      }
   }

   private void checkClosed() throws HornetQException
   {
      if (closed)
      {
         throw new HornetQException(HornetQException.OBJECT_CLOSED, "Consumer is closed");
      }
   }

   private void callOnMessage() throws Exception
   {
      if (closing || stopped)
      {
         return;
      }

      session.workDone();

      // We pull the message from the buffer from inside the Runnable so we can ensure priority
      // ordering. If we just added a Runnable with the message to the executor immediately as we get it
      // we could not do that

      ClientMessageInternal message;

      // Must store handler in local variable since might get set to null
      // otherwise while this is executing and give NPE when calling onMessage
      MessageHandler theHandler = handler;

      if (theHandler != null)
      {
         if (rateLimiter != null)
         {
            rateLimiter.limit();
         }

         synchronized (this)
         {
            message = buffer.poll();
         }

         if (message != null)
         {
            if (message.containsProperty(ClientConsumerImpl.FORCED_DELIVERY_MESSAGE))
            {
               //Ignore, this could be a relic from a previous receiveImmediate();
               return;
            }
            
            boolean expired = message.isExpired();

            flowControlBeforeConsumption(message);

            if (!expired)
            {
               onMessageThread = Thread.currentThread();

               if (ClientConsumerImpl.trace)
               {
                  ClientConsumerImpl.log.trace("Calling handler.onMessage");
               }
               theHandler.onMessage(message);

               if (ClientConsumerImpl.trace)
               {
                  ClientConsumerImpl.log.trace("Handler.onMessage done");
               }

               if (message.isLargeMessage())
               {
                  message.discardBody();
               }
            }
            else
            {
               session.expire(id, message.getMessageID());
            }

            // If slow consumer, we need to send 1 credit to make sure we get another message
            if (clientWindowSize == 0)
            {
               startSlowConsumer();
            }
         }
      }
   }

   /**
    * @param message
    * @throws HornetQException
    */
   private void flowControlBeforeConsumption(final ClientMessageInternal message) throws HornetQException
   {
      // Chunk messages will execute the flow control while receiving the chunks
      if (message.getFlowControlSize() != 0)
      {
         flowControl(message.getFlowControlSize(), true);
      }
   }

   private void doCleanUp(final boolean sendCloseMessage) throws HornetQException
   {
      try
      {
         if (closed)
         {
            return;
         }

         // We need an extra flag closing, since we need to prevent any more messages getting queued to execute
         // after this and we can't just set the closed flag to true here, since after/in onmessage the message
         // might be acked and if the consumer is already closed, the ack will be ignored
         closing = true;

         // Now we wait for any current handler runners to run.
         waitForOnMessageToComplete(true);

         if (currentLargeMessageController != null)
         {
            currentLargeMessageController.cancel();
            currentLargeMessageController = null;
         }

         closed = true;

         synchronized (this)
         {
            if (receiverThread != null)
            {
               // Wake up any receive() thread that might be waiting
               notify();
            }

            handler = null;

            receiverThread = null;
         }

         flushAcks();

         clearBuffer();

         if (sendCloseMessage)
         {
            channel.sendBlocking(new SessionConsumerCloseMessage(id));
         }
      }
      catch (Throwable t)
      {
         // Consumer close should always return without exception
      }

      session.removeConsumer(this);
   }

   private void clearBuffer()
   {
      buffer.clear();
   }

   private void doAck(final ClientMessageInternal message) throws HornetQException
   {
      ackBytes = 0;

      lastAckedMessage = null;

      session.acknowledge(id, message.getMessageID());
   }

   // Inner classes
   // --------------------------------------------------------------------------------

   private class Runner implements Runnable
   {
      public void run()
      {
         try
         {
            callOnMessage();
         }
         catch (Exception e)
         {
            ClientConsumerImpl.log.error("Failed to call onMessage()", e);

            lastException = e;
         }

      }
   }

}
