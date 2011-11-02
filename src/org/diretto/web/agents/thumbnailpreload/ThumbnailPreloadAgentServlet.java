package org.diretto.web.agents.thumbnailpreload;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

import org.apache.commons.configuration.XMLConfiguration;
import org.diretto.api.client.JavaClient;
import org.diretto.api.client.JavaClientManager;
import org.diretto.api.client.base.external.JacksonConverter;
import org.diretto.api.client.external.processing.ProcessingService;
import org.diretto.api.client.external.processing.ProcessingServiceID;
import org.diretto.api.client.main.core.entities.DocumentID;
import org.diretto.api.client.main.feed.FeedService;
import org.diretto.api.client.main.feed.FeedServiceID;
import org.diretto.api.client.main.feed.event.DocumentListener;
import org.diretto.api.client.session.SystemSession;
import org.diretto.api.client.util.ConfigUtils;
import org.diretto.api.client.util.URLTransformationUtils;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.data.Protocol;
import org.restlet.engine.Engine;
import org.restlet.engine.converter.ConverterHelper;
import org.restlet.resource.ClientResource;
import org.restlet.resource.ResourceException;

/**
 * This is the main class of the {@code ThumbnailPreloadAgent}. <br/><br/>
 * 
 * <i>Annotation:</i> Give attention that the configuration files of the
 * {@code JavaClient}, the {@code JavaClientFeedPlugin} and the
 * {@code JavaClientProcessingPlugin} are correctly configured (e.g. that the
 * {@code Cache} of the {@code JavaClient} is deactivated) before the
 * {@code ThumbnailPreloadAgent} will be deployed.
 * 
 * @author Tobias Schlecht
 */
public class ThumbnailPreloadAgentServlet extends HttpServlet
{
	private static final long serialVersionUID = 8381734096020857427L;

	private static final String CONFIG_FILE = "org/diretto/web/agents/thumbnailpreload/config.xml";

	private XMLConfiguration xmlConfiguration;
	private URL apiBaseURL;
	private SystemSession systemSession;
	private JavaClient javaClient;
	private FeedService feedService;
	private ProcessingService processingService;
	private List<String> thumbnailSizes;
	private Client restletClient;

	@Override
	@SuppressWarnings("unchecked")
	public void init(ServletConfig config) throws ServletException
	{
		xmlConfiguration = ConfigUtils.getXMLConfiguration(CONFIG_FILE);

		String apiBaseURLString = xmlConfiguration.getString("api-base-url");

		URL initAPIBaseURL = null;

		try
		{
			initAPIBaseURL = new URL(apiBaseURLString);
		}
		catch(MalformedURLException e)
		{
			e.printStackTrace();
		}

		apiBaseURL = URLTransformationUtils.adjustAPIBaseURL(initAPIBaseURL);

		String systemUserEmailAddress = xmlConfiguration.getString("system-user/email-address");
		String systemUserPassword = xmlConfiguration.getString("system-user/password");

		systemSession = JavaClientManager.INSTANCE.getSystemSession(apiBaseURL, systemUserEmailAddress, systemUserPassword);
		javaClient = JavaClientManager.INSTANCE.getJavaClient(systemSession);

		feedService = (FeedService) javaClient.getService(FeedServiceID.INSTANCE);
		processingService = (ProcessingService) javaClient.getService(ProcessingServiceID.INSTANCE);

		thumbnailSizes = (List<String>) xmlConfiguration.getList("thumbnail-sizes/size");

		initializeRestletClient();
		adjustFeedService();
	}

	/**
	 * Initializes the <i>Restlet</i> {@link Client} of this
	 * {@code ThumbnailPreloadAgent}.
	 */
	private void initializeRestletClient()
	{
		Context restletContext = new Context();

		String[] names = xmlConfiguration.getStringArray("restlet-client/connector-parameters/parameter/@name");
		String[] values = xmlConfiguration.getStringArray("restlet-client/connector-parameters/parameter/@value");

		for(int i = 0; i < names.length; i++)
		{
			restletContext.getParameters().add(names[i], values[i]);
		}

		restletClient = new Client(restletContext, Protocol.valueOf(xmlConfiguration.getString("restlet-client/connector-protocol")));

		List<ConverterHelper> converters = Engine.getInstance().getRegisteredConverters();
		converters.clear();
		converters.add(new JacksonConverter());

		try
		{
			restletClient.start();
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}
	}

	/**
	 * Adjusts the {@link FeedService} corresponding to this
	 * {@code ThumbnailPreloadAgent}.
	 */
	private void adjustFeedService()
	{
		feedService.addDocumentListener(new DocumentListener()
		{
			@Override
			public void onDocumentAdded(final DocumentID documentID)
			{
				if(documentID == null)
				{
					return;
				}

				for(final String size : thumbnailSizes)
				{
					new Thread(new Runnable()
					{
						@Override
						public void run()
						{
							String encodedUniqueResourceURLString = "";

							try
							{
								encodedUniqueResourceURLString = URLEncoder.encode(documentID.getUniqueResourceURL().toExternalForm(), "UTF-8");
							}
							catch(UnsupportedEncodingException e)
							{
								e.printStackTrace();
							}

							String urlString = processingService.getServiceURL().toExternalForm() + "/process/generic/thumbnail?item=" + encodedUniqueResourceURLString + "&async=true&size=" + size;

							ClientResource clientResource = new ClientResource(urlString);

							clientResource.setNext(restletClient);

							try
							{
								clientResource.get();
							}
							catch(ResourceException e)
							{
								System.err.println("[ThumbnailPreloadAgent ThumbnailPreloadAgentServlet] " + e.getStatus().getCode());

								return;
							}

							int statusCode = clientResource.getResponse().getStatus().getCode();

							if(statusCode != 200 && statusCode != 202 && statusCode != 303)
							{
								System.err.println("[ThumbnailPreloadAgent ThumbnailPreloadAgentServlet] " + clientResource.getResponse().getStatus().getCode());
							}
						}
					}).start();
				}
			}
		});
	}
}
