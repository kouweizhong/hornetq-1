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

package org.hornetq.tests.integration.jms.server.management;

import java.util.Map;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.TopicSubscriber;

import junit.framework.Assert;

import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.jms.HornetQJMSClient;
import org.hornetq.api.jms.management.SubscriptionInfo;
import org.hornetq.api.jms.management.TopicControl;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.remoting.impl.invm.InVMConnectorFactory;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.HornetQServers;
import org.hornetq.jms.client.HornetQDestination;
import org.hornetq.jms.client.HornetQTopic;
import org.hornetq.jms.server.impl.JMSServerManagerImpl;
import org.hornetq.tests.integration.management.ManagementControlHelper;
import org.hornetq.tests.integration.management.ManagementTestBase;
import org.hornetq.tests.unit.util.InVMContext;
import org.hornetq.tests.util.RandomUtil;
import org.hornetq.utils.json.JSONArray;

/**
 * A TopicControlTest
 *
 * @author <a href="jmesnil@redhat.com">Jeff Mesnil</a>
 * 
 * Created 13 nov. 2008 16:50:53
 *
 *
 */
public class TopicControlTest extends ManagementTestBase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   private HornetQServer server;

   private JMSServerManagerImpl serverManager;

   private String clientID;

   private String subscriptionName;

   protected HornetQTopic topic;

   private String topicBinding = "/topic/" + RandomUtil.randomString();

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testGetAttributes() throws Exception
   {
      TopicControl topicControl = createManagementControl();

      Assert.assertEquals(topic.getTopicName(), topicControl.getName());
      Assert.assertEquals(topic.getAddress(), topicControl.getAddress());
      Assert.assertEquals(topic.isTemporary(), topicControl.isTemporary());
      Object[] bindings = topicControl.getJNDIBindings();
      assertEquals(1, bindings.length);
      Assert.assertEquals(topicBinding , bindings[0]);
   }

   public void testGetXXXSubscriptionsCount() throws Exception
   {
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      // 1 non-durable subscriber, 2 durable subscribers
      JMSUtil.createConsumer(connection_1, topic);

      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();
      Assert.assertEquals(3, topicControl.getSubscriptionCount());
      Assert.assertEquals(1, topicControl.getNonDurableSubscriptionCount());
      Assert.assertEquals(2, topicControl.getDurableSubscriptionCount());

      connection_1.close();
      connection_2.close();
      connection_3.close();
   }

   public void testGetXXXMessagesCount() throws Exception
   {
      // 1 non-durable subscriber, 2 durable subscribers
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createConsumer(connection_1, topic);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();

      Assert.assertEquals(0, topicControl.getMessageCount());
      Assert.assertEquals(0, topicControl.getNonDurableMessageCount());
      Assert.assertEquals(0, topicControl.getDurableMessageCount());

      JMSUtil.sendMessages(topic, 2);

      Assert.assertEquals(3 * 2, topicControl.getMessageCount());
      Assert.assertEquals(1 * 2, topicControl.getNonDurableMessageCount());
      Assert.assertEquals(2 * 2, topicControl.getDurableMessageCount());

      connection_1.close();
      connection_2.close();
      connection_3.close();
   }

   public void testListXXXSubscriptionsCount() throws Exception
   {
      // 1 non-durable subscriber, 2 durable subscribers
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      MessageConsumer cons = JMSUtil.createConsumer(connection_1, topic);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      TopicSubscriber subs1 = JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      TopicSubscriber subs2 = JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();
      Assert.assertEquals(3, topicControl.listAllSubscriptions().length);
      Assert.assertEquals(1, topicControl.listNonDurableSubscriptions().length);
      Assert.assertEquals(2, topicControl.listDurableSubscriptions().length);
      
      String json = topicControl.listAllSubscriptionsAsJSON();
      System.out.println("Json: " + json);
      JSONArray jsonArray = new JSONArray(json);
      
      assertEquals(3, jsonArray.length());

      connection_1.close();
      connection_2.close();
      connection_3.close();
   }

   public void testListXXXSubscriptionsAsJSON() throws Exception
   {
      // 1 non-durable subscriber, 2 durable subscribers
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createConsumer(connection_1, topic);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();
      String jsonString = topicControl.listDurableSubscriptionsAsJSON();
      SubscriptionInfo[] infos = SubscriptionInfo.from(jsonString);
      Assert.assertEquals(2, infos.length);
      Assert.assertEquals(clientID, infos[0].getClientID());
      Assert.assertEquals(subscriptionName, infos[0].getName());
      Assert.assertEquals(clientID, infos[1].getClientID());
      Assert.assertEquals(subscriptionName + "2", infos[1].getName());

      jsonString = topicControl.listNonDurableSubscriptionsAsJSON();
      infos = SubscriptionInfo.from(jsonString);
      Assert.assertEquals(1, infos.length);
      Assert.assertEquals(null, infos[0].getClientID());
      Assert.assertEquals(null, infos[0].getName());

      jsonString = topicControl.listAllSubscriptionsAsJSON();
      infos = SubscriptionInfo.from(jsonString);
      Assert.assertEquals(3, infos.length);

      connection_1.close();
      connection_2.close();
      connection_3.close();
   }

   public void testCountMessagesForSubscription() throws Exception
   {
      String key = "key";
      long matchingValue = RandomUtil.randomLong();
      long unmatchingValue = matchingValue + 1;

      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

      JMSUtil.sendMessageWithProperty(session, topic, key, matchingValue);
      JMSUtil.sendMessageWithProperty(session, topic, key, unmatchingValue);
      JMSUtil.sendMessageWithProperty(session, topic, key, matchingValue);

      TopicControl topicControl = createManagementControl();

      Assert.assertEquals(3, topicControl.getMessageCount());

      Assert.assertEquals(2, topicControl.countMessagesForSubscription(clientID, subscriptionName, key + " =" +
                                                                                                   matchingValue));
      Assert.assertEquals(1, topicControl.countMessagesForSubscription(clientID, subscriptionName, key + " =" +
                                                                                                   unmatchingValue));

      connection.close();
   }

   public void testCountMessagesForUnknownSubscription() throws Exception
   {
      String unknownSubscription = RandomUtil.randomString();

      TopicControl topicControl = createManagementControl();

      try
      {
         topicControl.countMessagesForSubscription(clientID, unknownSubscription, null);
         Assert.fail();
      }
      catch (Exception e)
      {
      }
   }

   public void testCountMessagesForUnknownClientID() throws Exception
   {
      String unknownClientID = RandomUtil.randomString();

      TopicControl topicControl = createManagementControl();

      try
      {
         topicControl.countMessagesForSubscription(unknownClientID, subscriptionName, null);
         Assert.fail();
      }
      catch (Exception e)
      {
      }
   }

   public void testDropDurableSubscriptionWithExistingSubscription() throws Exception
   {
      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      TopicControl topicControl = createManagementControl();
      Assert.assertEquals(1, topicControl.getDurableSubscriptionCount());

      connection.close();

      topicControl.dropDurableSubscription(clientID, subscriptionName);

      Assert.assertEquals(0, topicControl.getDurableSubscriptionCount());
   }

   public void testDropDurableSubscriptionWithUnknownSubscription() throws Exception
   {
      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      TopicControl topicControl = createManagementControl();
      Assert.assertEquals(1, topicControl.getDurableSubscriptionCount());

      try
      {
         topicControl.dropDurableSubscription(clientID, "this subscription does not exist");
         Assert.fail("should throw an exception");
      }
      catch (Exception e)
      {

      }

      Assert.assertEquals(1, topicControl.getDurableSubscriptionCount());

      connection.close();
   }

   public void testDropAllSubscriptions() throws Exception
   {
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      connection_1.setClientID(clientID);
      Session sess1 = connection_1.createSession(false, Session.AUTO_ACKNOWLEDGE);
      TopicSubscriber durableSubscriber_1 = sess1.createDurableSubscriber(topic, subscriptionName);

      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      connection_2.setClientID(clientID + "2");
      Session sess2 = connection_1.createSession(false, Session.AUTO_ACKNOWLEDGE);
      TopicSubscriber durableSubscriber_2 = sess2.createDurableSubscriber(topic, subscriptionName + "2");
      
      connection_1.start();
      connection_2.start();

      Session sess = connection_1.createSession(false, Session.AUTO_ACKNOWLEDGE);
      MessageProducer prod = sess.createProducer(topic);
      
      TextMessage msg1 = sess.createTextMessage("tst1");
      prod.send(msg1);
      
      assertNotNull(durableSubscriber_1.receive(5000));
      assertNotNull(durableSubscriber_2.receive(5000));
      
      connection_1.close();
      connection_2.close();

      TopicControl topicControl = createManagementControl();

      Assert.assertEquals(2, topicControl.getSubscriptionCount());
      topicControl.dropAllSubscriptions();

      Assert.assertEquals(0, topicControl.getSubscriptionCount());

      connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      connection_1.setClientID(clientID);
      sess = connection_1.createSession(false, Session.AUTO_ACKNOWLEDGE);
      prod = sess.createProducer(topic);
      TextMessage msg2 = sess.createTextMessage("tst2");
      prod.send(msg2);
      
   }

   public void testRemoveAllMessages() throws Exception
   {
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_1, topic, clientID, subscriptionName);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName + "2");

      JMSUtil.sendMessages(topic, 3);

      TopicControl topicControl = createManagementControl();
      Assert.assertEquals(3 * 2, topicControl.getMessageCount());

      int removedCount = topicControl.removeMessages(null);
      Assert.assertEquals(3 * 2, removedCount);
      Assert.assertEquals(0, topicControl.getMessageCount());

      connection_1.close();
      connection_2.close();
   }

   public void testListMessagesForSubscription() throws Exception
   {
      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      JMSUtil.sendMessages(topic, 3);

      TopicControl topicControl = createManagementControl();
      Map<String, Object>[] messages = topicControl.listMessagesForSubscription(HornetQDestination.createQueueNameForDurableSubscription(clientID,
                                                                                                                                         subscriptionName));
      Assert.assertEquals(3, messages.length);

      connection.close();
   }

   public void testListMessagesForSubscriptionAsJSON() throws Exception
   {
      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      String[] ids = JMSUtil.sendMessages(topic, 3);

      TopicControl topicControl = createManagementControl();
      String jsonString = topicControl.listMessagesForSubscriptionAsJSON(HornetQDestination.createQueueNameForDurableSubscription(clientID,
                                                                                                                                  subscriptionName));
      Assert.assertNotNull(jsonString);
      JSONArray array = new JSONArray(jsonString);
      Assert.assertEquals(3, array.length());
      for (int i = 0; i < 3; i++)
      {
         Assert.assertEquals(ids[i], array.getJSONObject(i).get("JMSMessageID"));
      }

      connection.close();
   }

   public void testListMessagesForSubscriptionWithUnknownClientID() throws Exception
   {
      String unknownClientID = RandomUtil.randomString();

      TopicControl topicControl = createManagementControl();

      try
      {
         topicControl.listMessagesForSubscription(HornetQDestination.createQueueNameForDurableSubscription(unknownClientID,
                                                                                                           subscriptionName));
         Assert.fail();
      }
      catch (Exception e)
      {
      }
   }

   public void testListMessagesForSubscriptionWithUnknownSubscription() throws Exception
   {
      String unknownSubscription = RandomUtil.randomString();

      TopicControl topicControl = createManagementControl();

      try
      {
         topicControl.listMessagesForSubscription(HornetQDestination.createQueueNameForDurableSubscription(clientID,
                                                                                                           unknownSubscription));
         Assert.fail();
      }
      catch (Exception e)
      {
      }
   }
   
   public void testGetMessagesAdded() throws Exception
   {
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createConsumer(connection_1, topic);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();

      Assert.assertEquals(0, topicControl.getMessagesAdded());

      JMSUtil.sendMessages(topic, 2);

      Assert.assertEquals(3 * 2, topicControl.getMessagesAdded());

      connection_1.close();
      connection_2.close();
      connection_3.close();
   }
   
   public void testGetMessagesDelivering() throws Exception
   {
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      MessageConsumer cons_1 = JMSUtil.createConsumer(connection_1, topic, Session.CLIENT_ACKNOWLEDGE);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      MessageConsumer cons_2 = JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName, Session.CLIENT_ACKNOWLEDGE);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      MessageConsumer cons_3 = JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2", Session.CLIENT_ACKNOWLEDGE);

      TopicControl topicControl = createManagementControl();

      assertEquals(0, topicControl.getDeliveringCount());

      JMSUtil.sendMessages(topic, 2);

      assertEquals(0, topicControl.getDeliveringCount());
      
      connection_1.start();
      connection_2.start();
      connection_3.start();

      Message msg_1 = null;
      Message msg_2 = null;
      Message msg_3 = null;
      for (int i = 0; i < 2; i++)
      {
         msg_1 = cons_1.receive(5000);
         assertNotNull(msg_1);
         msg_2 = cons_2.receive(5000);
         assertNotNull(msg_2);
         msg_3 = cons_3.receive(5000);         
         assertNotNull(msg_3);
      }

      assertEquals(3 * 2, topicControl.getDeliveringCount());

      msg_1.acknowledge();
      assertEquals(2 * 2, topicControl.getDeliveringCount());
      msg_2.acknowledge();
      assertEquals(1 * 2, topicControl.getDeliveringCount());
      msg_3.acknowledge();
      assertEquals(0, topicControl.getDeliveringCount());
      
      connection_1.close();
      connection_2.close();
      connection_3.close();
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      Configuration conf = createBasicConfig();
      conf.setSecurityEnabled(false);
      conf.setJMXManagementEnabled(true);
      conf.getAcceptorConfigurations()
          .add(new TransportConfiguration("org.hornetq.core.remoting.impl.invm.InVMAcceptorFactory"));
      server = HornetQServers.newHornetQServer(conf, mbeanServer, false);
      server.start();

      serverManager = new JMSServerManagerImpl(server);
      serverManager.start();
      serverManager.setContext(new InVMContext());
      serverManager.activated();

      clientID = RandomUtil.randomString();
      subscriptionName = RandomUtil.randomString();

      String topicName = RandomUtil.randomString();
      serverManager.createTopic(false, topicName, topicBinding);
      topic = (HornetQTopic)HornetQJMSClient.createTopic(topicName);
   }

   @Override
   protected void tearDown() throws Exception
   {
      serverManager.stop();

      server.stop();

      serverManager = null;

      server = null;

      topic = null;

      super.tearDown();
   }

   protected TopicControl createManagementControl() throws Exception
   {
      return ManagementControlHelper.createTopicControl(topic, mbeanServer);
   }

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
