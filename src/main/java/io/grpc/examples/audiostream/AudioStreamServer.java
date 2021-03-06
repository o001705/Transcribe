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

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

public class AudioStreamServer {
  private static final Logger logger =
      Logger.getLogger(AudioStreamServer.class.getName());
  private static String ProviderType = PropertyReader.getProperty("Server.DefaultTranscriber"); 

  /* As new Transcrbers are added no need to touch this file
   * New Transcriber class can be added to  the properties file
   * Transcriber will be picked-up from here
   */
  private static TranscriptionProvider getTranscriptionProvider(String type) {
	  String providerClass = PropertyReader.getProperty("Server.Transcriber." + type);
	  providerClass = TranscriptionProvider.class.getPackage().getName()+ "." + providerClass;
	  logger.info("Transcription Provider class = " + providerClass);
	  try {
	  TranscriptionProvider result =(TranscriptionProvider) 
			  						Class.forName(providerClass).getConstructor().newInstance();
	  return result;
	  } catch (Exception e) {
		  e.printStackTrace();
		  System.exit(0);
	  }
	  return null;
  }

  public static void main(String[] args) throws InterruptedException, IOException {
    if (args.length > 0) {
    	if (args[0].startsWith("A")) {
	    	ProviderType = "A";
		    logger.info("Starting AWS Transcribe..");
    	}
    	else if (args[0].startsWith("G")) {
        	ProviderType = "G";
    	    logger.info("Starting Google Transcribe...");
    	}
    	else {
    	    logger.warning("Unknown Provider! Starting Default Provider ...");
    	}
    }
    else {
	    logger.info("No explicit provider! Starting Default  ...");
    }

    AudioStreamerGrpc.AudioStreamerImplBase svc = new AudioStreamerGrpc.AudioStreamerImplBase() {
    	@Override
    	public void setMetaData(MetaDataRequest req, StreamObserver<MetaDataResponse> responseObserver ) {
    		logger.info("Got Request to set Metadata for " + req.getSessionID());
            // Send a response.
    		MetaDataResponse reply = MetaDataResponse.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
    	}
    	
      @Override
      public StreamObserver<AudioRequest> audioStream(final StreamObserver<Empty> responseObserver) {
    	BlockingQueue<TranscriptResponse> sharedQueue;
   	    sharedQueue = new LinkedBlockingQueue();
        final ServerCallStreamObserver<Empty> serverCallStreamObserver = (ServerCallStreamObserver<Empty>) responseObserver;
        serverCallStreamObserver.disableAutoInboundFlowControl();

        final TranscriptionProvider trans = getTranscriptionProvider(ProviderType);  
        
		if ( (trans != null) && trans.connectToProvider("") )
		    trans.openTranscriptionEngine("", sharedQueue);
        
		class ResponseHandler implements Runnable {
        	@Override 
        	public void run() {
        		while (true) {
        			try {
	        			if (sharedQueue != null) {
	        				TranscriptResponse r = sharedQueue.take();
	        				System.out.printf("Transcription Response --> %.02f: %s [confidence: %.2f]\n",
				                r.timeStamp,
				                r.transcript,
				                r.confidenceScore);
	        			}
        			} catch (InterruptedException e) {
        			}
        		}
        	}
        }
        Thread RespThread = new Thread (new ResponseHandler());
        RespThread.start();
        
        // Once Server Thread is ready to handle Requests from gRPC Clients 
        class OnReadyHandler implements Runnable {
          private boolean wasReady = false;
          @Override
          public void run() {
            if (serverCallStreamObserver.isReady() && !wasReady) {
                wasReady = true;
                logger.info("READY");
                serverCallStreamObserver.request(1);
            }
          }
        }
        final OnReadyHandler onReadyHandler = new OnReadyHandler();
        serverCallStreamObserver.setOnReadyHandler(onReadyHandler);

        // Give gRPC a StreamObserver that can observe and process incoming requests.
        return new StreamObserver<AudioRequest>() {
          @Override
          public void onNext(AudioRequest request) {
            // Process the request and send a response or an error.
            try {
              // Accept and enqueue the request.
              ByteString buffer = request.getAudio();

              trans.Transcribe(buffer.toByteArray());

              // Send a response.
              Empty reply = Empty.getDefaultInstance();
              responseObserver.onNext(reply);

              if (serverCallStreamObserver.isReady()) {
                serverCallStreamObserver.request(1);
              } else {
                onReadyHandler.wasReady = false;
              }
            } catch (Throwable throwable) {
              //throwable.printStackTrace();
              responseObserver.onError(
                  Status.UNKNOWN.withDescription("Error handling request").withCause(throwable).asException());
            }
          }

          @Override
          public void onError(Throwable t) {
            trans.closeTranscriptionEngine();
            responseObserver.onCompleted();
          }

          @Override
          public void onCompleted() {
            // Signal the end of work when the client ends the request stream.
            logger.info("COMPLETED Client Request. Closing Transcription Engine instance");
            trans.closeTranscriptionEngine();
            responseObserver.onCompleted();
          }
        };
      }
    };

    final Server server = ServerBuilder
        .forPort(Integer.parseInt(PropertyReader.getProperty("gRPC.port")))
        .addService(svc)
        .build()
        .start();

    logger.info("Listening on " + server.getPort());

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("Shutting down...");
        try {
            server.shutdown().awaitTermination(Integer.parseInt(PropertyReader.getProperty("Server.ShutdownWaitTimeSec")), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
      }
    });
    server.awaitTermination();
  }
}
