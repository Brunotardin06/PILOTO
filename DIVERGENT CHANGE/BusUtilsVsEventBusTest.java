public class BusUtilsVsEventBusTest extends BaseTest {

    /* ----------------------------------------------------------- helpers */

    private static void registerPair(BusUtilsVsEventBusTest t) {
        EventBus.getDefault().register(t);
        BusUtils.register(t);
    }

    private static void unregisterPair(BusUtilsVsEventBusTest t) {
        EventBus.getDefault().unregister(t);
        BusUtils.unregister(t);
    }

    private static long runBenchmark(int iterations, Runnable task) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) task.run();
        return System.currentTimeMillis() - start;
    }

    /* -------------------------------------------------------- bench config */

    private record BenchConfig(String name, int rounds, int iterations) { }

    /* ------------------------------------------------------------ benchmark e re-registro */

    public void compareRegister10000Times() {
        List<BusUtilsVsEventBusTest> eventBusTests = new ArrayList<>();
        List<BusUtilsVsEventBusTest> busUtilsTests = new ArrayList<>();

        compareWithEventBus(new BenchConfig("Register 10000 times.", 10, 10000), new CompareCallback() {
            @Override public void runEventBus() {
                BusUtilsVsEventBusTest t = new BusUtilsVsEventBusTest();
                EventBus.getDefault().register(t);
                eventBusTests.add(t);
            }
            @Override public void runBusUtils() {
                BusUtilsVsEventBusTest t = new BusUtilsVsEventBusTest();
                BusUtils.register(t);
                busUtilsTests.add(t);
            }
            @Override public void restState() {
                eventBusTests.forEach(EventBus.getDefault()::unregister);
                eventBusTests.clear();
                busUtilsTests.forEach(BusUtils::unregister);
                busUtilsTests.clear();
            }
        });
    }

    public void comparePostTo1Subscriber1000000Times() {
        comparePostTemplate("Post to 1 subscriber 1000000 times.", 1, 1_000_000);
    }

    public void comparePostTo100Subscribers100000Times() {
        comparePostTemplate("Post to 100 subscribers 100000 times.", 100, 100_000);
    }

    private void comparePostTemplate(String name, int subscribeNum, int postTimes) {
        List<BusUtilsVsEventBusTest> tests = new ArrayList<>();
        for (int i = 0; i < subscribeNum; i++) {
            BusUtilsVsEventBusTest t = new BusUtilsVsEventBusTest();
            registerPair(t);
            tests.add(t);
        }

        compareWithEventBus(new BenchConfig(name, 10, postTimes), new CompareCallback() {
            @Override public void runEventBus() { EventBus.getDefault().post("EventBus"); }
            @Override public void runBusUtils() { BusUtils.post("busUtilsFun", "BusUtils"); }
            @Override public void restState() { }
        });

        tests.forEach(BusUtilsVsEventBusTest::unregisterPair);
    }


    
public record BenchResult(String name, long eventBusAvg, long busUtilsAvg) { }

/* ------------------------- benchmark-----------*/
   private BenchResult compareWithEventBus(BenchConfig cfg, CompareCallback cb) {
    	long[][] dur = new long[2][cfg.rounds()];
    	for (int i = 0; i < cfg.rounds(); i++) {
        	dur[0][i] = runBenchmark(cfg.iterations(), cb::runEventBus);
        	dur[1][i] = runBenchmark(cfg.iterations(), cb::runBusUtils);
        	cb.restState();
   	}
    	long ebAvg = Arrays.stream(dur[0]).sum() / cfg.rounds();
    	long buAvg = Arrays.stream(dur[1]).sum() / cfg.rounds();
    	return new BenchResult(cfg.name(), ebAvg, buAvg);
	}

    BenchResult r = compareWithEventBus(cfg, cb);
    System.out.println(r.name() + "\nEventBus: " + r.eventBusAvg()
                                 + " ms\nBusUtils: " + r.busUtilsAvg() + " ms");


    /* ------------------------------------------------------ compare hook --------------*/

    public interface CompareCallback {
        void runEventBus();
        void runBusUtils();
        void restState();
    }

    /* ----------------------------------------------------------- stubs --- */

    @Subscribe public void eventBusFun(String param) { }
    @BusUtils.Bus(tag = "busUtilsFun") public void busUtilsFun(String param) { }
    @Before public void setUp() throws Exception {
        ReflectUtils.reflect(BusUtils.class)
                .method("getInstance")
                .method("registerBus", "busUtilsFun",
                        BusUtilsVsEventBusTest.class.getName(), "busUtilsFun",
                        String.class.getName(), "param", false, "POSTING");
    }
}
