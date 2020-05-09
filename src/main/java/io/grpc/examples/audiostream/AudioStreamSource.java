package io.grpc.examples.audiostream;

import java.lang.Boolean;

public interface AudioStreamSource {
	public static final int MONO = 1;
	public static final int STEREO = 2;
	
	public void setupSource (String setupParam);
	public Boolean openStream(int bufferSize);
	public byte[] getNextBuffer();
	public Boolean isStreamOpen();
	public void closeStream();
}
