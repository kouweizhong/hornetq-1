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

package org.hornetq.jms.client;

import java.util.Enumeration;
import java.util.NoSuchElementException;

import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueBrowser;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.core.logging.Logger;

/**
 * HornetQ implementation of a JMS QueueBrowser.
 * 
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:andy.taylor@jboss.org">Andy Taylor</a>
 *
 * $Id: HornetQQueueBrowser.java 9219 2010-05-10 20:35:28Z clebert.suconic@jboss.com $
 */
public class HornetQQueueBrowser implements QueueBrowser
{
   // Constants ------------------------------------------------------------------------------------

   private static final Logger log = Logger.getLogger(HornetQQueueBrowser.class);

   // Static ---------------------------------------------------------------------------------------

   // Attributes -----------------------------------------------------------------------------------

   private final ClientSession session;

   private ClientConsumer consumer;

   private final HornetQQueue queue;

   private SimpleString filterString;

   // Constructors ---------------------------------------------------------------------------------

   protected HornetQQueueBrowser(final HornetQQueue queue, final String messageSelector, final ClientSession session) throws JMSException
   {
      this.session = session;
      this.queue = queue;
      if (messageSelector != null)
      {
         filterString = new SimpleString(SelectorTranslator.convertToHornetQFilterString(messageSelector));
      }
   }

   // QueueBrowser implementation -------------------------------------------------------------------

   public void close() throws JMSException
   {
      if (consumer != null)
      {
         try
         {
            consumer.close();
         }
         catch (HornetQException e)
         {
            throw JMSExceptionHelper.convertFromHornetQException(e);
         }
      }
   }

   public Enumeration getEnumeration() throws JMSException
   {
      try
      {
         close();

         consumer = session.createConsumer(queue.getSimpleAddress(), filterString, true);

         return new BrowserEnumeration();
      }
      catch (HornetQException e)
      {
         throw JMSExceptionHelper.convertFromHornetQException(e);
      }

   }

   public String getMessageSelector() throws JMSException
   {
      return filterString == null ? null : filterString.toString();
   }

   public Queue getQueue() throws JMSException
   {
      return queue;
   }

   // Public ---------------------------------------------------------------------------------------

   @Override
   public String toString()
   {
      return "HornetQQueueBrowser->" + consumer;
   }

   // Package protected ----------------------------------------------------------------------------

   // Protected ------------------------------------------------------------------------------------

   // Private --------------------------------------------------------------------------------------

   // Inner classes --------------------------------------------------------------------------------

   private class BrowserEnumeration implements Enumeration
   {
      ClientMessage current = null;

      public boolean hasMoreElements()
      {
         if (current == null)
         {
            try
            {
               current = consumer.receiveImmediate();
            }
            catch (HornetQException e)
            {
               return false;
            }
         }
         return current != null;
      }

      public Object nextElement()
      {
         HornetQMessage msg;
         if (hasMoreElements())
         {
            ClientMessage next = current;
            current = null;
            msg = HornetQMessage.createMessage(next, session);
            try
            {
               msg.doBeforeReceive();
            }
            catch (Exception e)
            {
               HornetQQueueBrowser.log.error("Failed to create message", e);

               return null;
            }
            return msg;
         }
         else
         {
            throw new NoSuchElementException();
         }
      }
   }
}
