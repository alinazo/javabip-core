package org.bip.executor;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;

import lsr.concurrence.provided.tests.ClientInputReader;
import lsr.concurrence.provided.tests.ClientOutputWriter;
import lsr.concurrence.provided.tests.InputChecker;

public class TestPubSub implements Runnable {

	public void run() {

		String host = "localhost";
		int port = 7676;
		System.out.println("Test with server " + host + ":" + port);

		ArrayList<String> topics = new ArrayList<String>();
		ArrayList<String> msgs = new ArrayList<String>();
		topics.add("epfl");
		topics.add("concurrence");
		msgs.add("bonjour");
		msgs.add("hello");
		int error = 0;

		try {
			Socket connection = new Socket(host, port);
			ClientOutputWriter output = new ClientOutputWriter(connection.getOutputStream());
			InputChecker inputCheck = new InputChecker(new ClientInputReader(connection.getInputStream()));

			// A simple test one topic
			// sequence: subscribe - publish - unsubscribe
			// System.out.println("**** TEST 1 ***");
			// error = 0;
			// output.subscribeTo(topics.get(0));
			// error += inputCheck.checkSubscribe(topics.get(0));
			// output.publish(topics.get(0), msgs.get(0));
			// error += inputCheck.checkPublish(topics.get(0), msgs.get(0));
			// output.unsubscribeTo(topics.get(0));
			// error += inputCheck.checkUnsubscribe(topics.get(0));

			// with two topics
			System.out.println("**** TEST 2 ***");
			output.subscribeTo(topics.get(0));
			// error += inputCheck.checkSubscribe(topics.get(0));
			output.subscribeTo(topics.get(1));
			// error += inputCheck.checkSubscribe(topics.get(1));

			output.publish(topics.get(0), msgs.get(0));
			// error += inputCheck.checkPublish(topics.get(0), msgs.get(0));
			output.publish(topics.get(1), msgs.get(1));
			// error += inputCheck.checkPublish(topics.get(1), msgs.get(1));

			output.unsubscribeTo(topics.get(0));
			// error += inputCheck.checkUnsubscribe(topics.get(0));

			output.publish(topics.get(0), msgs.get(1)); // no check as we are not supposed to
			// receive anything
			output.publish(topics.get(1), msgs.get(0));
			// error += inputCheck.checkPublish(topics.get(1), msgs.get(0));

			output.unsubscribeTo(topics.get(1));
			// error += inputCheck.checkUnsubscribe(topics.get(1));

			System.err.println("Number of errors: " + error);

			assertEquals("Number of error is zero ", 0, error);
		} catch (IOException e) {
			System.err.println("Fail to accept client connection");

		}
	}


}
