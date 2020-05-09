package io.grpc.examples.audiostream;

import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.speech.v1p1beta1.RecognitionConfig;
import com.google.cloud.speech.v1p1beta1.SpeechClient;
import com.google.cloud.speech.v1p1beta1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1p1beta1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1p1beta1.StreamingRecognitionResult;
import com.google.cloud.speech.v1p1beta1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1p1beta1.StreamingRecognizeResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;

import java.lang.Math;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class GoogleTranscriptionProvider implements TranscriptionProvider {
	
	private static final int STREAMING_LIMIT = 290000; // ~5 minutes
	private BlockingQueue<TranscriptResponse> sharedQueue;
	
	private StreamingRecognitionConfig streamingRecognitionConfig;
    private ResponseObserver<StreamingRecognizeResponse> responseObserver = null;
    private SpeechClient client = null;
    private ClientStream<StreamingRecognizeRequest> clientStream = null;
    private long startTime = 0;
    private StreamingRecognizeRequest request;
    
    private static int restartCounter = 0;
    private static ArrayList<ByteString> audioInput = new ArrayList<ByteString>();
    private static ArrayList<ByteString> lastAudioInput = new ArrayList<ByteString>();
    private static int resultEndTimeInMS = 0;
    private static int isFinalEndTime = 0;
    private static int finalRequestEndTime = 0;
    private static boolean newStream = true;
    private static double bridgingOffset = 0;
    private static boolean lastTranscriptWasFinal = false;
    private static StreamController referenceToStreamController;
    private static ByteString tempByteString;
	

	public Boolean connectToProvider(String params) {
		Boolean retVal = false;
		
		RecognitionConfig recognitionConfig =
		      RecognitionConfig.newBuilder()
		          .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
		          .setLanguageCode("en-US")
		          .setSampleRateHertz(16000)
		          .build();
		
		streamingRecognitionConfig =
		      StreamingRecognitionConfig.newBuilder()
		          .setConfig(recognitionConfig)
		          .setInterimResults(true)
		          .build();
		try {
			  client = SpeechClient.create();
			  retVal = true;
		} catch (Exception e) {
		}
		return retVal;
	}
	
	public Boolean openTranscriptionEngine(String params,BlockingQueue<TranscriptResponse> queue ) {
		Boolean retVal = false;
		sharedQueue = queue;
		try {
			request = StreamingRecognizeRequest.newBuilder()
						.setStreamingConfig(streamingRecognitionConfig)
						.build(); // The first request in a streaming call has to be a config

			clientStream = client.streamingRecognizeCallable().splitCall(processResponse());

		    clientStream.send(request);
		    startTime = System.currentTimeMillis();
		    retVal = true;
		} catch (Exception e) {
		}
		return retVal;
	}
	
	public Boolean Transcribe(byte[] stream) { 
		long estimatedTime = System.currentTimeMillis() - startTime;

		if (estimatedTime >= STREAMING_LIMIT) {
			restartClient();
		} else {
			if ((newStream) && (lastAudioInput.size() > 0)) {
				double chunkTime = STREAMING_LIMIT / lastAudioInput.size();
		      	if (chunkTime != 0) {
		    	  	if (bridgingOffset < 0) {
		        		bridgingOffset = 0;
		        	}
		        	if (bridgingOffset > finalRequestEndTime) {
		        		bridgingOffset = finalRequestEndTime;
		        	}
		        	int chunksFromMS = (int) Math.floor((finalRequestEndTime
		                - bridgingOffset) / chunkTime);
		        	bridgingOffset = (int) Math.floor((lastAudioInput.size()
		                - chunksFromMS) * chunkTime);
		        	for (int i = chunksFromMS; i < lastAudioInput.size(); i++) {
		        		request = StreamingRecognizeRequest.newBuilder()
		        					.setAudioContent(lastAudioInput.get(i))
		        					.build();
		          		clientStream.send(request);
		        	}
		      	}
		      	newStream = false;
		    }
		
		    tempByteString = ByteString.copyFrom(stream);
		
		    request =
		        StreamingRecognizeRequest.newBuilder()
		            .setAudioContent(tempByteString)
		            .build();
		    audioInput.add(tempByteString);
		}
		clientStream.send(request);
		return true;
	}
	
	public TranscriptResponse getOutput() {
		TranscriptResponse retVal = null;
		if (sharedQueue.size() > 0) {
			try {
			retVal = sharedQueue.take();
			} catch (InterruptedException e) {
				retVal = null;
			}
		}
		return retVal;
	}
	
	public void closeTranscriptionEngine() {
		
	}

	
	protected ResponseObserver<StreamingRecognizeResponse> processResponse() {
		return responseObserver = new ResponseObserver<StreamingRecognizeResponse>() {
			ArrayList<StreamingRecognizeResponse> responses = new ArrayList<>();
			
			public void onStart(StreamController controller) {
			  referenceToStreamController = controller;
			}
			
			public void onResponse(StreamingRecognizeResponse response) {
			  responses.add(response);
			  StreamingRecognitionResult result = response.getResultsList().get(0);
			  Duration resultEndTime = result.getResultEndTime();
			  resultEndTimeInMS = (int) ((resultEndTime.getSeconds() * 1000)
			          + (resultEndTime.getNanos() / 1000000));
			  double correctedTime = resultEndTimeInMS - bridgingOffset
			          + (STREAMING_LIMIT * restartCounter);
			
			  SpeechRecognitionAlternative alternative = result.getAlternativesList().get(0);
			  if (result.getIsFinal()) {
/*			    System.out.printf("%s: %s [confidence: %.2f]\n",
			                convertMillisToDate(correctedTime),
			                alternative.getTranscript(),
			                alternative.getConfidence()
			        );
*/			        TranscriptResponse resp = new TranscriptResponse();
			        resp.confidenceScore = alternative.getConfidence();
			        resp.timeStamp = correctedTime;
			        resp.transcript = alternative.getTranscript();
			        sharedQueue.add(resp);
			        isFinalEndTime = resultEndTimeInMS;
			        lastTranscriptWasFinal = true;
			      } else {
			        lastTranscriptWasFinal = false;
			      }
			    }
			
			    public void onComplete() {
			    }
			
			    public void onError(Throwable t) {
			    }
			};
	}

	private void restartClient() {
        clientStream.closeSend();
        referenceToStreamController.cancel(); // remove Observer

        if (resultEndTimeInMS > 0) {
          finalRequestEndTime = isFinalEndTime;
        }
        resultEndTimeInMS = 0;

        lastAudioInput = null;
        lastAudioInput = audioInput;
        audioInput = new ArrayList<ByteString>();

        restartCounter++;

        if (!lastTranscriptWasFinal) {
          System.out.print('\n');
        }

        newStream = true;

        clientStream = client.streamingRecognizeCallable().splitCall(responseObserver);

        request =
            StreamingRecognizeRequest.newBuilder()
                  .setStreamingConfig(streamingRecognitionConfig)
                  .build();

        System.out.printf("%d: RESTARTING REQUEST\n", restartCounter * STREAMING_LIMIT);
        startTime = System.currentTimeMillis();
	}
	
	private static String convertMillisToDate(double milliSeconds) {
	    long millis = (long) milliSeconds;
	    DecimalFormat format = new DecimalFormat();
	    format.setMinimumIntegerDigits(2);
	    return String.format("%s:%s /",
            format.format(TimeUnit.MILLISECONDS.toMinutes(millis)),
            format.format(TimeUnit.MILLISECONDS.toSeconds(millis)
                    - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)))
	    		);
	  }
}
