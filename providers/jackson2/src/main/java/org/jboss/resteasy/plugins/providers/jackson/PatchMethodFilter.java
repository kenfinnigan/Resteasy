package org.jboss.resteasy.plugins.providers.jackson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.annotation.Priority;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.BadRequestException;

import org.jboss.resteasy.core.ResourceMethodInvoker;
import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.specimpl.MultivaluedTreeMap;
import org.jboss.resteasy.spi.ApplicationException;
import org.jboss.resteasy.spi.Failure;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.HttpResponseCodes;
import org.jboss.resteasy.spi.Registry;
import org.jboss.resteasy.spi.ResourceInvoker;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import com.github.fge.jsonpatch.mergepatch.JsonMergePatch;

/*
* @author <a href="mailto:ema@redhat.com">Jim Ma</a>
*/
@Provider
@Priority(Integer.MAX_VALUE)
public class PatchMethodFilter implements ContainerRequestFilter
{
   //TODO:thse should go to jaxrs spec apis
   public static final String APPLICATION_JSON_MERGE_PATCH_JSON = "application/merge-patch+json";

   public static final MediaType APPLICATION_JSON_MERGE_PATCH_JSON_TYPE = new MediaType("application",
         "merge-patch+json");

   @Override
   @SuppressWarnings(
   {"rawtypes", "unchecked"})
   public void filter(ContainerRequestContext requestContext) throws IOException
   {
      if (requestContext.getMethod().equals("PATCH")
            && (MediaType.APPLICATION_JSON_PATCH_JSON_TYPE.isCompatible(requestContext.getMediaType())
            || APPLICATION_JSON_MERGE_PATCH_JSON_TYPE.isCompatible(requestContext.getMediaType())))
      {
         HttpRequest request = ResteasyContext.getContextData(HttpRequest.class);
         request.setHttpMethod("GET");
         HttpResponse response = ResteasyContext.getContextData(HttpResponse.class);
         Registry methodRegistry = ResteasyContext.getContextData(Registry.class);
         ResourceInvoker resourceInovker = methodRegistry.getResourceInvoker(request);
         try
         {
            ResourceMethodInvoker methodInvoker = (ResourceMethodInvoker) resourceInovker;
            if (resourceInovker == null)
            {
               throw new ProcessingException("Get method not found and patch method failed");
            }
            Object object = methodInvoker.invokeDryRun(request, response).toCompletableFuture().getNow(null);
            ByteArrayOutputStream tmpOutputStream = new ByteArrayOutputStream();
            MessageBodyWriter msgBodyWriter = ResteasyProviderFactory.getInstance().getMessageBodyWriter(
                  object.getClass(), object.getClass(), methodInvoker.getMethodAnnotations(),
                  MediaType.APPLICATION_JSON_TYPE);
            msgBodyWriter.writeTo(object, object.getClass(), object.getClass(), methodInvoker.getMethodAnnotations(),
                  MediaType.APPLICATION_JSON_TYPE, new MultivaluedTreeMap<String, Object>(), tmpOutputStream);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode targetJson = mapper.readValue(tmpOutputStream.toByteArray(), JsonNode.class);

            JsonNode result = null;
            if (MediaType.APPLICATION_JSON_PATCH_JSON_TYPE.isCompatible(requestContext.getMediaType()))
            {
               JsonPatch patch = JsonPatch.fromJson(mapper.readValue(request.getInputStream(), JsonNode.class));
               result = patch.apply(targetJson);
            }
            else
            {
               final JsonMergePatch mergePatch = JsonMergePatch.fromJson(mapper.readValue(request.getInputStream(),
                     JsonNode.class));
               result = mergePatch.apply(targetJson);
            }
            ByteArrayOutputStream targetOutputStream = new ByteArrayOutputStream();
            mapper.writeValue(targetOutputStream, result);
            request.setInputStream(new ByteArrayInputStream(targetOutputStream.toByteArray()));
            request.setHttpMethod("PATCH");
         }
         catch (ProcessingException pe)
         {
            Throwable c = pe.getCause();
            if (c != null && c instanceof ApplicationException)
            {
               c = c.getCause();
               if (c != null && c instanceof NotFoundException)
               {
                  throw (NotFoundException) c;
               }
            }
            throw pe;
         }
         catch (JsonMappingException | JsonParseException e)
         {
            throw new BadRequestException(e);
         }
         catch (JsonPatchException e)
         {
            throw new Failure(e, HttpResponseCodes.SC_CONFLICT);
         }
      }
   }
}
