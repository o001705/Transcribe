package io.grpc.examples.audiostream;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
 
public class PropertyReader {
	private static Properties prop;
	private static Boolean initialized = false;
	
	public static String getProperty(String key) {
		try{
			if (!initialized){
				PropertyReader r = new PropertyReader();
				r.initialize();
			}
			return prop.getProperty(key);
		}
		catch (IOException e) { 
			return null;
		}
	}
 
	private void initialize() throws IOException {
		InputStream inputStream = null;
 
		try {
			prop = new Properties();
			String propFileName = "config.properties";
 
			inputStream = getClass().getClassLoader().getResourceAsStream(propFileName);
 
			if (inputStream != null) {
				prop.load(inputStream);
			} else {
				throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
			}
			initialized = true;
		} catch (Exception e) {
			System.out.println("Exception: " + e);
		} finally {
			if (inputStream != null)
				inputStream.close();
		}
	}
}