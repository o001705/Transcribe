/*
 * Copyright 2017 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.audiostream;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.lang.Math;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import com.google.protobuf.ByteString;


class AudioBufferHandler implements Runnable {
	
    private  AudioStreamSource audioSource;
    private BlockingQueue<byte[]> sharedQueue;
    private int maxBufferSize;
    
    AudioBufferHandler(int SourceType, int bufSize, BlockingQueue<byte[]> queue ){
		audioSource = AudioStreamFactory.generateAudioSource(SourceType);
		maxBufferSize = bufSize;
		sharedQueue = queue;
    }
    @Override
    public void run() {
	    audioSource.setupSource("");
          byte[] data;
	    System.out.println("Audio Buffer Thread Running");
	    if (audioSource.openStream(maxBufferSize)) {
		    System.out.println("Audio Buffer Stream Opened");
	    	while (audioSource.isStreamOpen()) {
	    		data = audioSource.getNextBuffer();
	    		if (data != null) {
	    			try {
	    				sharedQueue.put(data.clone());
		            } catch (InterruptedException e) {
		              System.out.println("input buffering interrupted : " + e.getMessage());
		            }
	    		}
	    		else {
	    			audioSource.closeStream();
	    		}
	    	}
		    System.out.println("Audio Buffer Stream Closed");
	    }
	    else {
	    	System.out.println("Audio Buffer Stream Could not be opened");
	    }
    }
}

public class AudioStreamClient {
    private static final Logger logger =
        Logger.getLogger(AudioStreamClient.class.getName());
    
    // Create Shared Object between Threads
    private BlockingQueue<byte[]> sharedQueue;
    private AudioBufferHandler audiostreamhandler;
    AudioStreamerGrpc.AudioStreamerStub stub;
    private static final int BYTES_PER_BUFFER = 64000; // buffer size in bytes
	private static final CountDownLatch done = new CountDownLatch(1);
	private ManagedChannel channel;
	
    public void initialize() {
    	//Create a client object to forward Audio to gRPC Server
         channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext()
                .build();
	     stub = AudioStreamerGrpc.newStub(channel);
	    
    	// Create a shared buffer between threads to capture audio
    	sharedQueue = new LinkedBlockingQueue();

    	// Create a thread to handle Audio Stream
	    audiostreamhandler = new AudioBufferHandler(AudioStreamFactory.MIC,
		  										BYTES_PER_BUFFER,
		  										sharedQueue);
	    Thread audioThread = new Thread(audiostreamhandler);
	    try {
	    	audioThread.start();
	    } catch (Exception e){}
    }
    
    public void handleServerResponses() throws InterruptedException {
		ClientResponseObserver<AudioRequest, Empty> clientResponseObserver =
					new ClientResponseObserver<AudioRequest, Empty>() {
			ClientCallStreamObserver<AudioRequest> requestStream;
			@Override
	        public void beforeStart(final ClientCallStreamObserver<AudioRequest> requestStream) {
				this.requestStream = requestStream;
	            requestStream.disableAutoInboundFlowControl();
	            requestStream.setOnReadyHandler(new Runnable() {
	            	@Override
	            	public void run() {
	            		logger.info("<-- Server Ready for Next Set");
	            		while (true) {
	            			try {
	            				ByteString tempByteString = ByteString.copyFrom(sharedQueue.take());
	            				AudioRequest request = AudioRequest.newBuilder().setAudio(tempByteString).build();
	            				requestStream.onNext(request);
	            				Thread.sleep(5);
	            			} catch (InterruptedException e) {
	            				requestStream.onCompleted();
	            			}
	            		}
	            	}
	            });
			}

			@Override
			public void onNext(Empty value) {
				logger.info("<-- Next Value");
				// Signal the sender to send one message.
				requestStream.request(1);
			}

			@Override
			public void onError(Throwable t) {
				t.printStackTrace();
				done.countDown();
			}

			@Override
			public void onCompleted() {
				logger.info("All Done");
				done.countDown();
			}
        };
        stub.audioStream(clientResponseObserver);
        done.await();
    }
    
    public void close() throws InterruptedException {
        channel.shutdown();
        channel.awaitTermination(1, TimeUnit.SECONDS);
    }
    

    public static void main(String[] args) throws InterruptedException {
	    AudioStreamClient client = new AudioStreamClient();
	    client.initialize();
	    client.handleServerResponses();
	    client.close();
    }
}
