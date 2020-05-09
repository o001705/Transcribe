package io.grpc.examples.audiostream;

import java.io.*;
import javax.sound.sampled.*;

public class FileAudioStreamSource implements AudioStreamSource {
	private File inputFile;
	private InputStream inputStream;
	private Boolean isOpen;
	private int offset;
	private int bufferSize;
	AudioInputStream iStream;
    private byte[] data;
	
	public void setupSource (String setupParam) {
		System.out.println("File = " + setupParam);
        inputFile = new File(setupParam);
//        File f = new File()
	}
	
	
	public Boolean openStream(int bSize) {
		Boolean retVal = false;
		bufferSize = bSize;
		offset = 0;
		try {
		    AudioInputStream sourceIn = AudioSystem.getAudioInputStream(inputFile);
		    AudioFormat sourceFormat = sourceIn.getFormat();
	
		    // define a target AudioFormat that is likely to be supported by your audio hardware,
		    // i.e. 44.1kHz sampling rate and 16 bit samples.
		    iStream = AudioSystem.getAudioInputStream(
		            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 
		            				16000f, 
    		                        16, 
    		                        sourceFormat.getChannels(), 
    		                        sourceFormat.getFrameSize(), 
    		                        sourceFormat.getFrameRate(), 
    		                        false),
		            sourceIn);
		    
		    data = new byte[bufferSize];
			isOpen = true;
		    retVal = true;
		} catch (Exception e) {
		    System.err.println(e.getMessage());
		}
		return retVal;
	}
	
/**	

	public Boolean openStream(int bSize) {
		Boolean retVal = false;
		bufferSize = bSize;
		offset = 0;
		try {
			if (inputFile != null) {
	            inputStream = new FileInputStream(inputFile);
	            if (inputStream != null) {
	            	isOpen = true;
	            	retVal = true;
	            }
	            Thread.sleep(10000);
			}
		} catch (FileNotFoundException | InterruptedException e ){
			
		}
		return retVal;
	}
**/	
	public byte[] getNextBuffer() {
		byte[] retVal = null;
		try {
	        int numBytesRead = iStream.read(data);
	        if (numBytesRead > 0) {
	        	retVal = data;
	        }
	        else{
	        	iStream.close();
	        	isOpen = false;
	        }
		} catch (IOException e) {}
		return retVal;
	}

	public Boolean isStreamOpen() {
		return isOpen;
	}

	@Override
	public void closeStream() {
		data = null;
		isOpen = false;
	}
}

