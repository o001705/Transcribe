package io.grpc.examples.audiostream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.DataLine.Info;

public class MicAudioStreamSource implements AudioStreamSource {
	private int sampleRate;
	private int sampleSize;
	private int numChannels;
	private Boolean isSigned;
	private Boolean isBigEndian;
	private AudioFormat audioFormat;
    private static TargetDataLine targetDataLine;
    private byte[] data;
    private Boolean isOpen;
	
	public void setupSource (String setupParam) {
        // SampleRate:16000Hz, SampleSizeInBits: 16, Number of channels: 1, Signed: true,
        // bigEndian: false
		sampleRate = 16000;
		sampleSize = 16;
		numChannels = AudioStreamSource.MONO;
		isSigned = true;
		isBigEndian = false;
		
		isOpen = false;
		
        audioFormat = new AudioFormat(sampleRate, 
									  sampleSize,
									  numChannels,
									  isSigned,
									  isBigEndian);
	}
	
	public Boolean openStream(int bufferSize) {
		if (!isOpen){
			DataLine.Info targetInfo =
			          new Info(
			              TargetDataLine.class,
			              audioFormat); // Set the system information to read from the microphone audio stream
		
			if (!AudioSystem.isLineSupported(targetInfo)) {
			    System.out.println("Microphone not supported");
			}
			else {
			  // Target data line captures the audio stream the microphone produces.
				try {
					targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
					targetDataLine.open(audioFormat);
			        targetDataLine.start();
			        data = new byte[bufferSize];
					isOpen = true;
			        System.out.println("Start speaking...Press Ctrl-C to stop");
				} catch (Exception e) {
					isOpen = false;
				}
	        }
		}
		return isOpen;
	}

	public byte[] getNextBuffer() {
		byte[] retVal = null;
		
		if (targetDataLine.isOpen()) {
            int numBytesRead = targetDataLine.read(data, 0, data.length);
            if (numBytesRead > 0) {
            	retVal = data;
            }
		}
		return retVal;
	}
	
	public Boolean isStreamOpen() {
		return isOpen;
	}
	
	public void closeStream() {
		targetDataLine.stop();
		targetDataLine.close();
		data = null;
		isOpen = false;
	}
}
