package io.grpc.examples.audiostream;

import java.util.concurrent.BlockingQueue;

class TranscriptResponse {
	double confidenceScore;
	double timeStamp;
	String transcript;
}

public interface TranscriptionProvider {
	public static final int AWS= 1;
	public static final int GOOGLE= 2;
	public static final int MICROSOFT = 3;
	public static final int KRYPTON = 4;
	
	public Boolean connectToProvider(String params);
	public Boolean openTranscriptionEngine(String params, BlockingQueue<TranscriptResponse> sharedQueue);
	public Boolean Transcribe(byte[] stream);
	public TranscriptResponse getOutput();
	public void closeTranscriptionEngine();
}
