package com.iptiq.loadbalancer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URL;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Slf4j
@Service
public class LoadBalancerService {

    final String providerUrl = "http://provider-";

    private @Value("${invocation.method}")
    String invocationMethod;

    private @Value("${instance.per.provider}")
    int instancePerProvider;

    private @Value("${number.of.providers}")
    int numberOfProviders;

    private @Value("${array.of.providers}")
    Integer[] arrayOfProviders;

    BlockingQueue<Integer> livePortQueue = new LinkedBlockingQueue<>(10);
    HashMap<Integer, Integer> healthCheckMap = new HashMap<>();

    public String getUniqueProviderId() throws Exception {
        String result;
        Integer port = livePortQueue.poll(100, TimeUnit.MILLISECONDS);
        if (port == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Server is fully occupied");

        int counter = 1;
        while (healthCheckMap.containsKey(port)) {
            livePortQueue.put(port);
            if (counter == livePortQueue.size())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Server is fully occupied");
            port = livePortQueue.poll(100, TimeUnit.MILLISECONDS);
            counter++;
        }
        result = getUniqueIdFromProviders(port);

        /** Added sleep of 1 second to imitate real time delay **/
        Thread.sleep(1000);
        livePortQueue.put(port);

        return result;
    }

    public void check() {
        Arrays.stream(arrayOfProviders).forEach(
                port -> {
                    try (Socket socket =
                                 new Socket(new URL(providerUrl + port).getHost(), port)) {

                        if (healthCheckMap.size() > 0 && healthCheckMap.containsKey(port)) {
                            healthCheckMap.put(port, healthCheckMap.get(port) + 1);
                            if (healthCheckMap.get(port) == 2) {
                                log.info("Registered after 2 success health checks : " + port);
                                healthCheckMap.remove(port);
                            }
                        }
                    } catch (ConnectException e) {
                        log.error("Failed to connect to: " + providerUrl + port + ":" + port);
                        healthCheckMap.put(port, 0);
                    } catch (Exception ex) {
                        log.error("Exception: " + ex.getMessage() + "\n" + ex.getLocalizedMessage());
                        ex.printStackTrace();
                    }
                }
        );
    }

    @PostConstruct
    public void healthCheckScheduler() {
        /** Interval of 10 second to perform provider`s health check **/
        final long timeInterval = 10000;

        Runnable runnable = () -> {
            while (true) {
                check();

                try {
                    Thread.sleep(timeInterval);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();
    }

    @PostConstruct
    public void fillInitialPortQueue() {
        List<Integer> initialList = Arrays.asList(arrayOfProviders);
        List<Integer> concatList = new ArrayList<>();
        IntStream.range(1, instancePerProvider + 1).forEach($ -> concatList.addAll(initialList));
        if (invocationMethod.equals("Random"))
            Collections.shuffle(concatList);

        livePortQueue = new LinkedBlockingQueue<>(concatList);
    }

    public String getUniqueIdFromProviders(Integer port) {
        RestTemplate restTemplate = new RestTemplate();
        return restTemplate.getForObject(providerUrl + port + ":" + port + "/uniqueId/{port}", String.class, port);
    }
}
