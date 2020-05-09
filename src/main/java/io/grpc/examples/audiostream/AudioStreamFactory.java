package io.grpc.examples.audiostream;

public class AudioStreamFactory {
	public static final int MIC = 1;
	public static final int FILE = 2;
	
	public static AudioStreamSource generateAudioSource(int SourceType){
		AudioStreamSource retVal = null;
		if (SourceType== MIC){
			retVal = new MicAudioStreamSource();
		}
		return retVal;
	}
}
