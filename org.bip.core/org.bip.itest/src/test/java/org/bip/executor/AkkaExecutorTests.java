package org.bip.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

import javax.xml.bind.JAXBException;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.RoutePolicy;
import org.bip.api.BIPActor;
import org.bip.api.BIPEngine;
import org.bip.api.BIPGlue;
import org.bip.api.Executor;
import org.bip.engine.BIPCoordinatorImpl;
import org.bip.engine.DataCoordinatorKernel;
import org.bip.engine.api.BIPCoordinator;
import org.bip.engine.api.EngineFactory;
import org.bip.exceptions.BIPException;
import org.bip.executor.impl.akka.OrchestratedExecutorFactory;
import org.bip.glue.GlueBuilder;
import org.bip.glue.TwoSynchronGlueBuilder;
import org.bip.spec.ComponentA;
import org.bip.spec.ComponentB;
import org.bip.spec.ComponentC;
import org.bip.spec.Consumer;
import org.bip.spec.Feeder;
import org.bip.spec.HanoiGlueBuilder;
import org.bip.spec.HanoiMonitor;
import org.bip.spec.InitialServer;
import org.bip.spec.LeftHanoiPeg;
import org.bip.spec.MemoryMonitor;
import org.bip.spec.MiddleHanoiPeg;
import org.bip.spec.PSSComponent;
import org.bip.spec.Peer;
import org.bip.spec.RightHanoiPeg;
import org.bip.spec.Server;
import org.bip.spec.SwitchableRouteDataTransfers;
import org.bip.spec.Tracker;
import org.bip.spec.TwoDataProvider1;
import org.bip.spec.TwoDataProvider2;
import org.bip.spec.TwoDataTaker;
import org.bip.spec.diningphilosophers.DiningPhilosophersGlueBuilder;
import org.bip.spec.diningphilosophers.Fork;
import org.bip.spec.diningphilosophers.Philosophers;
import org.bip.spec.hanoi.HanoiOptimalMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import akka.actor.ActorSystem;

public class AkkaExecutorTests {

	ActorSystem system;
	EngineFactory engineFactory;
	
	@Before
	public void initialize() {

		system = ActorSystem.create("MySystem");
		engineFactory = new EngineFactory(system);

	}
	
	@After
	public void cleanup() {
		
		system.shutdown();
		
	}
	
	@Test
	public void akkaExecutorSimpleTest() {

		// Create BIP engine.
		BIPEngine engine = engineFactory.create("myEngine", new BIPCoordinatorImpl(system));

		// Create BIP Spec. 
		HanoiMonitor hanoiMonitor = new HanoiMonitor(3);
		
		BIPActor actor = engine.register(hanoiMonitor, "hanoiMonitor", false);
	
		assertNotNull("Actor Typed actor is not created properly", actor);

		engine.stop();
		engineFactory.destroy(engine);
		
	}

	@Test
	public void bipDataTransferTest() throws BIPException {
		
		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		BIPGlue bipGlue = new TwoSynchronGlueBuilder() {
			@Override
			public void configure() {

				synchron(SwitchableRouteDataTransfers.class, "on").to(MemoryMonitor.class, "add");
				synchron(SwitchableRouteDataTransfers.class, "finished").to(MemoryMonitor.class, "rm");
				
				port(SwitchableRouteDataTransfers.class, "off").acceptsNothing();
				port(SwitchableRouteDataTransfers.class, "off")	.requiresNothing();
				
				data(SwitchableRouteDataTransfers.class, "deltaMemoryOnTransition").to(MemoryMonitor.class, "memoryUsage");

			}

		}.build();

		CamelContext camelContext = new DefaultCamelContext();
		camelContext.setAutoStartup(false);

		SwitchableRouteDataTransfers route1 = new SwitchableRouteDataTransfers("1", camelContext);
		SwitchableRouteDataTransfers route2 = new SwitchableRouteDataTransfers("2", camelContext);
		SwitchableRouteDataTransfers route3 = new SwitchableRouteDataTransfers("3", camelContext);

		BIPActor actor1 = engine.register(route1, "1", true);
		BIPActor actor2 = engine.register(route2, "2", true);
		BIPActor actor3 = engine.register(route3, "3", true);
		
		final RoutePolicy routePolicy1 = createRoutePolicy(actor1);
		final RoutePolicy routePolicy2 = createRoutePolicy(actor2);
		final RoutePolicy routePolicy3 = createRoutePolicy(actor3);

		RouteBuilder builder = new RouteBuilder() {

			@Override
			public void configure() throws Exception {
				from("file:inputfolder1?delete=true").routeId("1")
						.routePolicy(routePolicy1).to("file:outputfolder1");

				from("file:inputfolder2?delete=true").routeId("2")
						.routePolicy(routePolicy2).to("file:outputfolder2");

				from("file:inputfolder3?delete=true").routeId("3")
						.routePolicy(routePolicy3).to("file:outputfolder3");
			}
			
		};
		
		try {
			camelContext.addRoutes(builder);
			camelContext.start();
		} catch (Exception e) {
			e.printStackTrace();
		}

		MemoryMonitor routeOnOffMonitor = new MemoryMonitor(200);
		final BIPActor executorM = engine.register(routeOnOffMonitor, "monitor", true);

		engine.specifyGlue(bipGlue);
		engine.start();

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		engine.execute();
		
		try {
			Thread.sleep(20000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}		
		
		engine.stop();
		engineFactory.destroy(engine);
				
		assertTrue("Route 1 has not made any transitions", route1.noOfEnforcedTransitions > 0);
		assertTrue("Route 2 has not made any transitions", route2.noOfEnforcedTransitions > 0);
		assertTrue("Route 3 has not made any transitions", route3.noOfEnforcedTransitions > 0);

	}
	
	// No asserts yet, just to see if the whole thing does not blow at
	// initialization time and due to first few cycles.
	@Test
	public void bipRandomLargerHannoiWithDataTest() throws JAXBException,
			BIPException {

		int size = 3;

		BIPGlue bipGlue4Hanoi = new org.bip.spec.hanoi.HanoiRandomGlueBuilder()
				.build();

		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		org.bip.spec.hanoi.HanoiPeg leftHanoiPeg = new org.bip.spec.hanoi.HanoiPeg(size, false);
		BIPActor actor1 = engine.register(leftHanoiPeg, "LeftHanoiPeg", false);
		
		org.bip.spec.hanoi.HanoiPeg middleHanoiPeg = new org.bip.spec.hanoi.HanoiPeg(size, true);
		BIPActor actor2 = engine.register(middleHanoiPeg, "MiddleHanoiPeg", false);
		
		org.bip.spec.hanoi.HanoiPeg rightHanoiPeg = new org.bip.spec.hanoi.HanoiPeg(size, true);
		// TODO, before with executors executor was using rightHanoiPeg.
		BIPActor actor3 = engine.register(rightHanoiPeg, "RightHanoiPeg", false);
				
		org.bip.spec.hanoi.HanoiPeg rightMiddleHanoiPeg = new org.bip.spec.hanoi.HanoiPeg(size, true);
		BIPActor actor4 = engine.register(rightMiddleHanoiPeg, "RightMiddleHanoiPeg", false);
				
		org.bip.spec.hanoi.HanoiPeg leftMiddleHanoiPeg = new org.bip.spec.hanoi.HanoiPeg(size, false);
		BIPActor actor5 = engine.register(leftMiddleHanoiPeg, "LeftMiddleHanoiPeg", false);				
				
		engine.specifyGlue(bipGlue4Hanoi);
		engine.start();
		
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.execute();

		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		engine.stop();
		engineFactory.destroy(engine);
		
		int noOfAllTransitions = leftHanoiPeg.noOfTransitions + rightHanoiPeg.noOfTransitions + leftMiddleHanoiPeg.noOfTransitions + 
								 rightMiddleHanoiPeg.noOfTransitions + middleHanoiPeg.noOfTransitions;

		assertTrue("Hanoi tower have seen progress of executing transitions", noOfAllTransitions > 0);
		
	}


	// No asserts yet, just to see if the whole thing does not blow at
	// initialization time and due to first few cycles.
	@Test
	public void bipRandomHannoiWithDataTest() throws JAXBException,
			BIPException {

		int size = 3;

		// BIP engine.
		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));
		
		BIPGlue bipGlue4Hanoi = new org.bip.spec.hanoi.HanoiRandomGlueBuilder()
				.build();

		org.bip.spec.hanoi.HanoiPeg leftHanoiPeg = new org.bip.spec.hanoi.HanoiPeg(size, false);
		BIPActor actor1 = engine.register(leftHanoiPeg, "LeftHanoiPeg", false);
		
		org.bip.spec.hanoi.HanoiPeg middleHanoiPeg = new org.bip.spec.hanoi.HanoiPeg(size, true);
		BIPActor actor2 = engine.register(middleHanoiPeg, "MiddleHanoiPeg", false);
		
		org.bip.spec.hanoi.HanoiPeg rightHanoiPeg = new org.bip.spec.hanoi.HanoiPeg(size, true);
		BIPActor actor3 = engine.register(rightHanoiPeg, "RightHanoiPeg", false);
		
		engine.specifyGlue(bipGlue4Hanoi);
		
		engine.start();

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		engine.execute();

		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		engine.stop();
		engineFactory.destroy(engine);
		
		int noOfAllTransitions = leftHanoiPeg.noOfTransitions + rightHanoiPeg.noOfTransitions + middleHanoiPeg.noOfTransitions;

		assertTrue("Hanoi tower have seen progress of executing transitions", noOfAllTransitions > 0);
		assertTrue("Number of component transition should sum up to an even number", noOfAllTransitions % 2 == 0);
				
	}

	
	@Test
	public void bipHannoiWithDataTestSize3() throws JAXBException, BIPException {

		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		int size = 3;

		BIPGlue bipGlue4Hanoi = new org.bip.spec.hanoi.HanoiOptimalGlueBuilder()
				.build();

		HanoiOptimalMonitor hanoiMonitor = new HanoiOptimalMonitor(size);
		BIPActor actor1 = engine.register(hanoiMonitor, "hanoiMonitor", false);
		
		org.bip.spec.hanoi.LeftHanoiPeg leftHanoiPeg = new org.bip.spec.hanoi.LeftHanoiPeg(
				size);
		BIPActor actor2 = engine.register(leftHanoiPeg, "LeftHanoiPeg", false);

		org.bip.spec.hanoi.MiddleHanoiPeg middleHanoiPeg = new org.bip.spec.hanoi.MiddleHanoiPeg(
				size);
		BIPActor actor3 = engine.register(middleHanoiPeg, "MiddleHanoiPeg", false);


		org.bip.spec.hanoi.RightHanoiPeg rightHanoiPeg = new org.bip.spec.hanoi.RightHanoiPeg(
				size);
		BIPActor actor4 = engine.register(rightHanoiPeg, "RightHanoiPeg", false);

		engine.specifyGlue(bipGlue4Hanoi);
		engine.start();
		
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.execute();

		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		System.out.println("Finished test, number of transitions " + hanoiMonitor.getNumberOfMoves());
		System.out.flush();
		
		engine.stop();
		engineFactory.destroy(engine);
		
		assertEquals("Hanoi tower has not reached its final state ", (int) Math.pow(2, size) - 1, hanoiMonitor.getNumberOfMoves());
		
	}

	@Test
	public void bipHannoiWithDataTestSize8() throws JAXBException, BIPException {

		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		int size = 8;

		BIPGlue bipGlue4Hanoi = new org.bip.spec.hanoi.HanoiOptimalGlueBuilder()
				.build();

		HanoiOptimalMonitor hanoiMonitor = new HanoiOptimalMonitor(size);
		BIPActor actor1 = engine.register(hanoiMonitor, "hanoiMonitor", false);
		
		
		org.bip.spec.hanoi.LeftHanoiPeg leftHanoiPeg = new org.bip.spec.hanoi.LeftHanoiPeg(
				size);
		BIPActor actor2 = engine.register(leftHanoiPeg, "LeftHanoiPeg", false);

		org.bip.spec.hanoi.MiddleHanoiPeg middleHanoiPeg = new org.bip.spec.hanoi.MiddleHanoiPeg(
				size);
		BIPActor actor3 = engine.register(middleHanoiPeg, "MiddleHanoiPeg", false);


		org.bip.spec.hanoi.RightHanoiPeg rightHanoiPeg = new org.bip.spec.hanoi.RightHanoiPeg(
				size);
		BIPActor actor4 = engine.register(rightHanoiPeg, "RightHanoiPeg", false);

		engine.specifyGlue(bipGlue4Hanoi);
		engine.start();
		
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.execute();

		try {
			Thread.sleep(20000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		System.out.println("Finished test, number of transitions " + hanoiMonitor.getNumberOfMoves());
		System.out.flush();
		
		engine.stop();
		engineFactory.destroy(engine);
		
		assertEquals("Hanoi tower has not reached its final state ", (int) Math.pow(2, size) - 1, hanoiMonitor.getNumberOfMoves());
		
	}

	// It does not use data transfers but plenty of interactions and more ports.
	@Test
	public void akkaExecutorHannoiNoDataTransferswithActorEngineTest() {

		BIPEngine engine = engineFactory.create("myEngine", new BIPCoordinatorImpl(system));

		int size = 3;

		BIPGlue bipGlue4Hanoi = new HanoiGlueBuilder(size).build();

		HanoiMonitor hanoiMonitor = new HanoiMonitor(size);
		BIPActor actor1 = engine.register(hanoiMonitor, "hanoiMonitor", false);

		LeftHanoiPeg leftHanoiPeg = new LeftHanoiPeg(size);
		BIPActor actor2 = engine.register(leftHanoiPeg, "leftHanoi", false);

		MiddleHanoiPeg middleHanoiPeg = new MiddleHanoiPeg(size);
		BIPActor actor3 = engine.register(middleHanoiPeg, "middleHanoi", false);

		RightHanoiPeg rightHanoiPeg = new RightHanoiPeg(size);
		BIPActor actor4 = engine.register(rightHanoiPeg, "rightHanoi", false);

		engine.specifyGlue(bipGlue4Hanoi);
		engine.start();

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.execute();

		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		System.out.println("Finished test, number of transitions " + hanoiMonitor.getNumberOfMoves());
		System.out.flush();
		
		engine.stop();
		engineFactory.destroy(engine);
		
		assertEquals("Hanoi tower has not reached its final state ", (int) Math.pow(2, size) - 1, hanoiMonitor.getNumberOfMoves());

	}	


	@Test
	public void akkaExecutorHannoiTest() {

		int size = 8;

		BIPGlue bipGlue4Hanoi = new HanoiGlueBuilder(size).build();

		// TODO Discussion, here we do not use TypedActor to create an engine. Thus the BIP engine is not protected 
		// from multiple-thread calls against its functions. BIP engine is not guaranteed to be multiple-thread safe right?, so we should
		// remove this test as there is a similar one that does hanoi testing without data transfers.
		// BIP engine.
		BIPCoordinator engine = new BIPCoordinatorImpl(system);

		HanoiMonitor hanoiMonitor = new HanoiMonitor(size);
		BIPActor actor1 = engine.register(hanoiMonitor, "hanoiMonitor", false);

		LeftHanoiPeg leftHanoiPeg = new LeftHanoiPeg(size);
		BIPActor actor2 = engine.register(leftHanoiPeg, "leftHanoi", false);

		MiddleHanoiPeg middleHanoiPeg = new MiddleHanoiPeg(size);
		BIPActor actor3 = engine.register(middleHanoiPeg, "middleHanoi", false);

		RightHanoiPeg rightHanoiPeg = new RightHanoiPeg(size);
		BIPActor actor4 = engine.register(rightHanoiPeg, "rightHanoi", false);

		engine.specifyGlue(bipGlue4Hanoi);
		engine.start();

		engine.execute();

		try {
			Thread.sleep(30000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.stop();
		engineFactory.destroy(engine);
		
		assertEquals("Hanoi tower has not reached its final state ", (int) Math.pow(2, size) - 1, hanoiMonitor.getNumberOfMoves());
		
	}	

	
	// TODO, IMPROVEMENT When BIP protocol is optimized and wait no longer is used then enable this test.
	// Currently, this test will fail as spontaneous wait on one component blocks BIP engine cycle.
	@Test
	@Ignore
	public void testNoSpontaneousWaitBlockEverything() throws BIPException {

		BIPGlue bipGlue = new GlueBuilder() {
			@Override
			public void configure() {

				port(PSSComponent.class, "p").requires(ComponentB.class, "b");

				port(PSSComponent.class, "p").accepts(ComponentB.class, "b");

				port(ComponentB.class, "a").requiresNothing();
				port(ComponentB.class, "b").requires(PSSComponent.class, "p");
				
				// Just to keep the DataCoordinator happy to have something even if this BIP component is not instantiated.
				data(ComponentB.class, "memoryY").to(Consumer.class, "memoryUsage");

			}

		}.build();

		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		PSSComponent pssComponent = new PSSComponent(true);
		BIPActor pssExecutor = engine.register(pssComponent, "pssCompE", true);
		
		ComponentB bComponent = new ComponentB();
		BIPActor bExecutor = engine.register(bComponent, "bCompE", true);
		
		Consumer cComponent = new Consumer(100);
		BIPActor cExecutor = engine.register(cComponent, "cCompE", true);
		
		engine.specifyGlue(bipGlue);
		engine.start();

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		engine.execute();

		try {
			Thread.sleep(4000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.stop();
		engineFactory.destroy(engine);
		
		assertEquals("Spontaneous wait on one component has blocked all the components", bComponent.counterA > 0, true);
		
	}

	@Test
	public void ServersTest() {
		
		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		// TODO, PLEASE use BIP glue builders and not XML file.
		// TODO, BUG? BTW, BIP glue is not using class InitialServer only Server.
		BIPGlue bipGlue = createGlue("src/test/resources/bipGlueServers.xml");

		// TODO, Why both Classes use the same specType org.bip.spec.Server? Would not this cause huge problems on engine side?
		// or at least the second attempt to define type of the spec will be ignored? and thus all the components will actually have 
		// the same behavior associated?
		InitialServer server1 = new InitialServer(1);
		Server server2 = new Server(2);
		Server server3 = new Server(3);

		
		final BIPActor executor1 = engine.register(server1, "1", true);		
		final BIPActor executor2 = engine.register(server2, "2", true);
		final BIPActor executor3 = engine.register(server3, "3", true);


		engine.specifyGlue(bipGlue);
		engine.start();
		engine.execute();

		try {
			Thread.sleep(30000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.stop();
		engineFactory.destroy(engine);
		
	}

	@Test
	public void TrackerPeerTest()
	{		
				
		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));
		
		// TODO, PLEASE use BIP glue builders and not XML file.
		BIPGlue bipGlue = createGlue("src/test/resources/trackerPeerGlue.xml");
		
		Tracker tracker1 = new Tracker(1);
		Peer peer1a = new Peer(11);
		Peer peer1b = new Peer(12);
		Tracker tracker2 = new Tracker(2);
		Peer peer2a = new Peer(21);
		Peer peer2b = new Peer(22);

		final BIPActor executor1 = engine.register(tracker1, "1", true);
		final BIPActor executor1a = engine.register( peer1a, "11", true);
		final BIPActor executor1b = engine.register( peer1b, "12", true);
		final BIPActor executor2 = engine.register( tracker2, "2", true);
		final BIPActor executor2a = engine.register( peer2a, "21", true);
		final BIPActor executor2b = engine.register( peer2b, "22", true);
		
		engine.specifyGlue(bipGlue);
		engine.start();

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		engine.execute();
		
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		engine.stop();
		engineFactory.destroy(engine);
		
		assertTrue("Tracker 1 has not made any transitions", tracker1.noOfTransitions > 0);
		assertTrue("Tracker 2 has not made any transitions", tracker2.noOfTransitions > 0);
		
		assertTrue("Peer 1a has not made any transitions", peer1a.noOfTransitions > 0);
		assertTrue("Peer 2a has not made any transitions", peer2a.noOfTransitions > 0);
		assertTrue("Peer 1b has not made any transitions", peer1b.noOfTransitions > 0);
		assertTrue("Peer 2b has not made any transitions", peer2b.noOfTransitions > 0);
		
}
	
	@Test
	public void bipDataFeederConsumerTest() throws BIPException {

		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		BIPGlue bipGlue = createGlue("src/test/resources/bipGlueFeederConsumer.xml");

		Feeder feeder = new Feeder();
		BIPActor actor1 = engine.register(feeder, "feeder", true);
		
		Consumer consumer = new Consumer(350);
		BIPActor actor2 = engine.register(consumer, "consumer", true);
		
		engine.specifyGlue(bipGlue);
		engine.start();

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.execute();
		
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.stop();
		engineFactory.destroy(engine);
		
		assertTrue("Feeder has not made any transitions", feeder.noOfTransitions > 0);
		assertTrue("Consumer has not made any transitions", consumer.noOfTransitions > 0);
		
	}

	@Test
	public void bipDataAvailabilityTest() throws BIPException {
		
		BIPEngine engine = engineFactory.create("myEngine", new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		BIPGlue bipGlue = createGlue("src/test/resources/bipGlueDataAvailability.xml");

		ComponentA componentA = new ComponentA(250);
		ComponentB componentB = new ComponentB();
		ComponentC componentC = new ComponentC();
		
		BIPActor executorA = engine.register(componentA, "compA", true);
		
		BIPActor executorB = engine.register(componentB, "compB", true);
		
		BIPActor executorC = engine.register(componentC, "compC", true);
		

		engine.specifyGlue(bipGlue);
		engine.start();

		engine.execute();

		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.stop();
		engineFactory.destroy(engine);

		assertTrue("CompA has not made any transitions", componentA.noOfTransitions > 0);
		assertTrue("CompB has not made any transitions", componentB.noOfTransitions > 0);
		assertTrue("CompC has not made any transitions", componentC.noOfTransitions > 0);
		
	}
	
	
	@Test
	public void akkaExecutorPhilosopherwithDataTest() {

		BIPEngine engine = engineFactory.create("myEngine",
 new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		BIPGlue bipGlue4Philosophers = new DiningPhilosophersGlueBuilder().build();

		Fork f1 = new Fork(1);
		Fork f2 = new Fork(2);
		Fork f3 = new Fork(3);
		
		Philosophers p1 = new Philosophers(1, 2, true);
		Philosophers p2 = new Philosophers(2, 3, true);
		Philosophers p3 = new Philosophers(3, 1, true);
		
		
		BIPActor f1E = engine.register(f1, "f1E", true);
		BIPActor f2E = engine.register(f2, "f2E", true);
		BIPActor f3E = engine.register(f3, "f3E", true);
		
		BIPActor p1E = engine.register(p1, "p1E", true);
		BIPActor p2E = engine.register(p2, "p2E", true);
		BIPActor p3E = engine.register(p3, "p3E", true);

		engine.specifyGlue(bipGlue4Philosophers);
		engine.start();

		engine.execute();

		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		int noOfTimesUsed = f1.noOfTimesUsed() + f2.noOfTimesUsed() + f3.noOfTimesUsed();
		
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		int noOfTimesUsed2 = f1.noOfTimesUsed() + f2.noOfTimesUsed() + f3.noOfTimesUsed();

		engine.stop();
		engineFactory.destroy(engine);
		
		assertEquals("BIP engine could not progress the system.", true,	noOfTimesUsed2 > noOfTimesUsed);
		
	}	
	
	// TODO, This test is hitting the limitation of BIP engine concerning multiple transfers.
	// TODO, Explain exactly what is the limitation.
	@Test
	@Ignore
	public void bipTwoDataTest() throws BIPException {

		BIPEngine engine = engineFactory.create("myEngine",
 new DataCoordinatorKernel(new BIPCoordinatorImpl(system)));

		BIPGlue bipGlue = createGlue("src/test/resources/bipGlueTwoData.xml");

		TwoDataTaker componentA = new TwoDataTaker(100);
		TwoDataProvider1 componentB = new TwoDataProvider1();
		TwoDataProvider2 componentC = new TwoDataProvider2();

		final BIPActor executorA = engine.register(componentA, "compA", true);
		final BIPActor executorB = engine.register(componentB, "compB", true);
		final BIPActor executorC = engine.register(componentC, "compC", true);

		engine.specifyGlue(bipGlue);
		engine.start();

		engine.execute();

		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		engine.stop();
		engineFactory.destroy(engine);
		
		assertTrue("CompA has not made any transitions", componentA.noOfTransitions > 0);
		assertTrue("CompB has not made any transitions", componentB.noOfTransitions > 0);
		assertTrue("CompC has not made any transitions", componentC.noOfTransitions > 0);

	}
	
	private BIPGlue createGlue(String bipGlueFilename) {
		BIPGlue bipGlue = null;

		InputStream inputStream;
		try {
			inputStream = new FileInputStream(bipGlueFilename);

			bipGlue = GlueBuilder.fromXML(inputStream);

		} catch (FileNotFoundException e) {

			e.printStackTrace();
		}
		return bipGlue;
	}
	
	private RoutePolicy createRoutePolicy(final BIPActor actor1) {
		
		return  new RoutePolicy() {

			public void onInit(Route route) {
			}

			public void onExchangeDone(Route route, Exchange exchange) {

				actor1.inform("end");
			}

			public void onExchangeBegin(Route route, Exchange exchange) {
			}

			@Override
			public void onRemove(Route arg0) {
			}

			@Override
			public void onResume(Route arg0) {
			}

			@Override
			public void onStart(Route arg0) {
			}

			@Override
			public void onStop(Route arg0) {
			}

			@Override
			public void onSuspend(Route arg0) {
			}
		};

	}

}