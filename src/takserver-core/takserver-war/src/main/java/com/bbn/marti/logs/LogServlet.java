package com.bbn.marti.logs;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.mail.internet.MimeMessage;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.bbn.marti.remote.CoreConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.codec.Base64;

import com.bbn.marti.EsapiServlet;
import com.bbn.marti.HttpParameterConstraints;
import com.bbn.security.web.MartiValidatorConstants;


public class LogServlet extends EsapiServlet {
	
	@Autowired
	private PersistenceStore persistenceStore;

	@Autowired
	CoreConfig coreConfig;

	public enum QueryParameter {
		id,
		uid,
		callsign,
		platform,
		majorVersion,
		minorVersion
	}	
	
	protected Map<String, HttpParameterConstraints> requiredPostParameters;
	protected Map<String, HttpParameterConstraints> optionalPostParameters;
	protected Map<String, HttpParameterConstraints> requiredGetParameters;
	protected Map<String, HttpParameterConstraints> optionalGetParameters;
	private static final long serialVersionUID = 3520164783651907004L;
	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(LogServlet.class);
		
	@Override
	protected void initalizeEsapiServlet()
	{
		logger.debug("initializeEsapiServlet");
		
		requiredPostParameters = new HashMap<String, HttpParameterConstraints>();
		optionalPostParameters = new HashMap<String, HttpParameterConstraints>();		
		requiredPostParameters.put(QueryParameter.uid.name(), new HttpParameterConstraints(
				MartiValidatorConstants.Regex.MartiSafeString, MartiValidatorConstants.LONG_STRING_CHARS));
		requiredPostParameters.put(QueryParameter.callsign.name(), new HttpParameterConstraints(
				MartiValidatorConstants.Regex.MartiSafeString, MartiValidatorConstants.DEFAULT_STRING_CHARS));
		requiredPostParameters.put(QueryParameter.platform.name(), new HttpParameterConstraints(
				MartiValidatorConstants.Regex.MartiSafeString, MartiValidatorConstants.DEFAULT_STRING_CHARS));
		requiredPostParameters.put(QueryParameter.majorVersion.name(), new HttpParameterConstraints(
				MartiValidatorConstants.Regex.MartiSafeString, MartiValidatorConstants.DEFAULT_STRING_CHARS));
		requiredPostParameters.put(QueryParameter.minorVersion.name(), new HttpParameterConstraints(
				MartiValidatorConstants.Regex.MartiSafeString, MartiValidatorConstants.DEFAULT_STRING_CHARS));

		requiredGetParameters = new HashMap<String, HttpParameterConstraints>();
		optionalGetParameters = new HashMap<String, HttpParameterConstraints>();		
		requiredGetParameters.put(QueryParameter.id.name(), new HttpParameterConstraints(
				MartiValidatorConstants.Regex.MartiSafeString, MartiValidatorConstants.LONG_STRING_CHARS));
	}

	private HashMap<String, byte[]> extractMissionPackage(byte[] missionPackage) throws IOException {
		HashMap<String, byte[]> files = new HashMap<>();

		ZipEntry entry;
		final int BUFFER = 2048;

		ByteArrayInputStream bis = new ByteArrayInputStream(missionPackage);
		ZipInputStream zis = new ZipInputStream(new BufferedInputStream(bis));
		while ((entry = zis.getNextEntry()) != null) {

			// skip directories
			if (entry.isDirectory()) {
				continue;
			}

			// load in the file
			int count;
			byte data[] = new byte[BUFFER];
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			while ((count = zis.read(data, 0, BUFFER)) != -1) {
				bos.write(data, 0, count);
			}
			bos.flush();
			bos.close();

			files.put(new File(entry.getName()).getName(), bos.toByteArray());
		}
		zis.close();

		return files;
	}
		
    @Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
    	
    	try {
        	initAuditLog(request);
    		
            Map<String, String[]> httpParameters = validateParams("Log", request, response,
            		requiredPostParameters, optionalPostParameters);

            if(httpParameters == null)
                return;
    		
	      	String uid = getParameterValue(httpParameters, QueryParameter.uid.name());
	      	String callsign = getParameterValue(httpParameters, QueryParameter.callsign.name());
	      	String platform = getParameterValue(httpParameters, QueryParameter.platform.name());
	      	String majorVersion = getParameterValue(httpParameters, QueryParameter.majorVersion.name());
	      	String minorVersion = getParameterValue(httpParameters, QueryParameter.minorVersion.name());
	      	String encodedString = IOUtils.toString(request.getInputStream());
	    	      		      	
	      	final int BUFFER = 2048;
	      	byte[] zip = Base64.decode(encodedString.getBytes());

			HashMap<String, byte[]> missionPackage = extractMissionPackage(zip);
			Iterator it = missionPackage.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<String, byte[]> missionPackageEntry = (Map.Entry<String, byte[]>) it.next();

				// create a Log object and store in the db
				Log log = new Log();
				log.setUid(uid);
				log.setCallsign(callsign);
				log.setPlatform(platform);
				log.setMajorVersion(majorVersion);
				log.setMinorVersion(minorVersion);
				log.setContents(missionPackageEntry.getValue());
				log.setFilename(missionPackageEntry.getKey());
				persistenceStore.addLog(log);

				if (coreConfig.getRemoteConfiguration().getEmail() != null &&
					coreConfig.getRemoteConfiguration().getEmail().isLogAlertsEnabled()) {

					for (String extension : coreConfig.getRemoteConfiguration().getEmail().getLogAlertsExtension()) {
						if (log.getFilename().endsWith(extension)) {
							sendLogAlert(log.getContents());
							break;
						}
					}
				}
			}

    	}
    	catch (Exception e) {
    		logger.error("exception in doPost", e);
        	response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    	}
	    	
      	return;
    }

    private void sendLogAlert(byte[] feedback) {
		if (coreConfig.getRemoteConfiguration().getEmail() == null) {
			logger.error("missing email configuration in sendLogAlert");
			return;
		}

		//
		// inspect the log content as a zip file and look for any json content. attempt to extract
		// an email address from the json to add on the cc line of the alert email
		//
		String cc = null;
		String body = "";
		HashMap<String, byte[]> logAsZip = null;
		try {
			logAsZip = extractMissionPackage(feedback);
			Iterator it = logAsZip.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<String, byte[]> feedbackFile = (Map.Entry<String, byte[]>) it.next();
				if (feedbackFile.getKey().toLowerCase().contains(".json")) {
					final Map<String, Object> jsonMap =
							new ObjectMapper().readValue(feedbackFile.getValue(), Map.class);
					for (Map.Entry<String, Object> jsonEntry : jsonMap.entrySet()) {
						body += jsonEntry.getKey() + " : " + jsonEntry.getValue() + "\n";
						if (jsonEntry.getKey().equals("Email")) {
							cc = (String)jsonEntry.getValue();
						}
					}
					it.remove();
				}
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("exception extracting log as zip");
			}
		}

		if (body != null) {
			sendEmail(body, cc, logAsZip);
		}
	}

	private void sendEmail(String text, String cc, HashMap<String, byte[]> attachments) {
		try {

			JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
			mailSender.setHost(coreConfig.getRemoteConfiguration().getEmail().getHost());
			mailSender.setPort(coreConfig.getRemoteConfiguration().getEmail().getPort());
			mailSender.setUsername(coreConfig.getRemoteConfiguration().getEmail().getUsername());
			mailSender.setPassword(coreConfig.getRemoteConfiguration().getEmail().getPassword());

			if (mailSender == null) {
				logger.error("error getting mailSender!");
				return;
			}

			MimeMessage message = mailSender.createMimeMessage();
			message.setContent(text, "text/html");

			MimeMessageHelper helper = new MimeMessageHelper(message, true, "utf-8");
			helper.setFrom(coreConfig.getRemoteConfiguration().getEmail().getFrom());
			helper.setTo(coreConfig.getRemoteConfiguration().getEmail().getLogAlertsTo());

			if (cc != null) {
				helper.addCc(cc);
			}

			helper.setSubject(coreConfig.getRemoteConfiguration().getEmail().getLogAlertsSubject());
			helper.setText(text);

			if (attachments != null) {
				for (Map.Entry<String, byte[]> attachment : attachments.entrySet()) {
					helper.addAttachment(attachment.getKey(), new ByteArrayResource(attachment.getValue()));
				}
			}

			mailSender.send(message);

		} catch (Exception e) {
			logger.error("exception in sendEmail!", e);
		}
	}

    @Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
    	
        try {
        	initAuditLog(request);
        	
            Map<String, String[]> httpParameters = validateParams("Log", request, response,
            		requiredGetParameters, optionalGetParameters);

            if(httpParameters == null)
                return;
    		
	      	String id = getParameterValue(httpParameters, QueryParameter.id.name());
	      	String[] ids = id.split(",");

	      	//
	      	// return the single file in it's original form
	      	//
	      	if (ids.length == 1)
	      	{
	      		int logId = Integer.parseInt(ids[0]);
		      	Log log = persistenceStore.getLog(logId);
	
		        response.setContentType("text/plain");
		        int contentLength = log.getContents().length;
		        response.setContentLength(contentLength);
				String contentDisposition = "attachment; filename=" + ids[0] + "_" + log.getFilename();
				contentDisposition = validator.getValidInput("Content Disposition", contentDisposition, "Filename", MartiValidatorConstants.DEFAULT_STRING_CHARS, false);
				response.addHeader("Content-Disposition", contentDisposition);
		        response.getOutputStream().write(log.getContents());
	      	}
	      	
	      	//
	      	// return the selected files as a zip
	      	//
	      	else
	      	{
	      		ByteArrayOutputStream baos = new ByteArrayOutputStream();
	      		BufferedOutputStream bos = new BufferedOutputStream(baos);
	      		ZipOutputStream zos = new ZipOutputStream(bos);

	      		for (String nextId : ids)
	      		{
		      		int logId = Integer.parseInt(nextId);
			      	Log log = persistenceStore.getLog(logId);
			      	if (log == null) {
			      		logger.error("Unable to find logId : " + logId);
			      		continue;
					}

      		        zos.putNextEntry(new ZipEntry(nextId + "_" + log.getFilename()));
      		        zos.write(log.getContents());
      		        zos.closeEntry();
	      		}
      		    zos.close();
	      		
		        response.setContentType("application/zip");
		        int contentLength = baos.size();
		        response.setContentLength(contentLength);
		        
		        String date = new SimpleDateFormat("yyyy-MM-dd_hh-mm-ss").format(new Date());
		        String filename = "LogExport" + date + ".zip";
				response.addHeader(
						"Content-Disposition", 
						"attachment; filename=" + filename);	
		      	
		       response.getOutputStream().write(baos.toByteArray());
	      	}
	        
	    } catch (Exception e) {
	        logger.error("Exception!", e);
	        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
	    }        
    }
    
    @Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
        try {
        	initAuditLog(request);
        	
        	String id = request.getParameter("id");
        	persistenceStore.deleteLog(id);
        	
	    } catch (Exception e) {
	        logger.error("Exception!", e);
	        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
	    }        
    }    
}
