package io.grpc.examples.audiostream;

import java.util.concurrent.BlockingQueue;
import java.util.List;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.reactivestreams.Subscription;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.EventStreamAws4Signer;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient;
import software.amazon.awssdk.services.transcribestreaming.model.AudioStream;
import software.amazon.awssdk.services.transcribestreaming.model.LanguageCode;
import software.amazon.awssdk.services.transcribestreaming.model.MediaEncoding;
import software.amazon.awssdk.services.transcribestreaming.model.TranscriptEvent;
import software.amazon.awssdk.services.transcribestreaming.model.Result;
import software.amazon.awssdk.services.transcribestreaming.model.Alternative;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionRequest;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionResponseHandler;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.transcribestreaming.model.AudioEvent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;
import io.grpc.examples.audiostream.TranscriptionProvider;
import io.grpc.examples.audiostream.TranscriptResponse;

public class AWSTranscriptionProvider implements TranscriptionProvider {

    private TranscribeStreamingAsyncClient client;
    private AudioStreamPublisher requestStream;
    private PipedInputStream pinStream;
    private PipedOutputStream poutStream;
	private static BlockingQueue<TranscriptResponse> sharedQueue;
	
	
	public Boolean connectToProvider(String params) {
		Boolean retVal = false;
		try {
			client = getClient();
			pinStream = new PipedInputStream();
			poutStream = new PipedOutputStream();
			poutStream.connect(pinStream);
			retVal = true;
		} catch (Exception e) {}
		return retVal;
	}

	public Boolean openTranscriptionEngine(String params, BlockingQueue<TranscriptResponse> queue) {
		sharedQueue = queue;
        CompletableFuture<Void> result = client.startStreamTranscription(getRequest(16_000),
                new AudioStreamPublisher(pinStream),
//                new Publisher<AudioStream>(pinStream),
                getResponseHandler());
        return true;
	}

	public Boolean Transcribe(byte[] stream) {
		Boolean retVal = false;
		try {
			poutStream.write(stream);
			retVal = true;
		}catch (Exception e) {}
		return retVal;
	}

	public TranscriptResponse getOutput() {
		// To be implemented
		return null;
	}
	public void closeTranscriptionEngine() {
		client.close();
	}
	
	
    private static TranscribeStreamingAsyncClient getClient() {
        Region region = getRegion();
        String endpoint = "https://transcribestreaming." + region.toString().toLowerCase().replace('_','-') + ".amazonaws.com";
        try {
            return TranscribeStreamingAsyncClient.builder()
                    .credentialsProvider(getCredentials())
                    .endpointOverride(new URI(endpoint))
                    .region(region)
                    .build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI syntax for endpoint: " + endpoint);
        }
    }

    /**
     * Get region from default region provider chain, default to PDX (us-west-2)
     */
    private static Region getRegion() {
        Region region;
        try {
            region = new DefaultAwsRegionProviderChain().getRegion();
        } catch (SdkClientException e) {
            region = Region.US_EAST_1;
        }
        return region;
    }    

    /**
     * @return AWS credentials to be used to connect to Transcribe service. This example uses the default credentials
     * provider, which looks for environment variables (AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY) or a credentials
     * file on the system running this program.
     */
    private static AwsCredentialsProvider getCredentials() {
        return DefaultCredentialsProvider.create();
    }
    
    
    private static StartStreamTranscriptionRequest getRequest(Integer mediaSampleRateHertz) {
        return StartStreamTranscriptionRequest.builder()
                .languageCode(LanguageCode.EN_US.toString())
                .mediaEncoding(MediaEncoding.PCM)
                .mediaSampleRateHertz(mediaSampleRateHertz)
                .build();
    }
    
    private static StartStreamTranscriptionResponseHandler getResponseHandler() {
        return StartStreamTranscriptionResponseHandler.builder()
                .onResponse(r -> {
                    System.out.println("Received Initial response");
                })
                .onError(e -> {
                    System.out.println(e.getMessage());
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    System.out.println("Error Occurred: " + sw.toString());
                })
                .onComplete(() -> {
                    System.out.println("=== All records stream successfully ===");
                })
                .subscriber(event -> {
                    List<Result> results = ((TranscriptEvent) event).transcript().results();
                    if (results.size() > 0) {
                    	if (!results.get(0).isPartial()){
	                        if (!results.get(0).alternatives().get(0).transcript().isEmpty()) {
	                        	TranscriptResponse r = new TranscriptResponse();
	                        	r.transcript = results.get(0).alternatives().get(0).transcript();
	                        	r.confidenceScore = 0.80; //results.get(0).alternatives().get(0).confidence();
	                        	r.timeStamp = 233;//results.get(0).alternatives().get(0).items().get(0).startTme();
	                        	sharedQueue.add(r);
	                            System.out.println(results.get(0).alternatives().get(0).transcript());
	                        }
                    	}
                    }
                })
                .build();
    }
	
	
    /**
     * AudioStreamPublisher implements audio stream publisher.
     * AudioStreamPublisher emits audio stream asynchronously in a separate thread
     */
    protected static class AudioStreamPublisher implements Publisher<AudioStream> {
        private final InputStream inputStream;

        AudioStreamPublisher(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void subscribe(Subscriber<? super AudioStream> s) {
            s.onSubscribe(new ByteToAudioEventSubscription(s, inputStream));
        }
    }
}
