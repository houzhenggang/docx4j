/*
 *  Copyright 2012, Plutext Pty Ltd.
 *
 *  This file is part of docx4j.

    docx4j is licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.

    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */
package org.docx4j.openpackaging.io3.stores;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.IOUtils;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.contenttype.ContentTypeManager;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.parts.CustomXmlDataStoragePart;
import org.docx4j.openpackaging.parts.JaxbXmlPart;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.XmlPart;
import org.docx4j.openpackaging.parts.WordprocessingML.BinaryPart;
import org.docx4j.openpackaging.parts.WordprocessingML.OleObjectBinaryPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

/**
 * Load a zipped up package from a file or input stream;
 * save it to some output stream.
 *
 * @author jharrop
 * @since 3.0
 */
public class ZipPartStore implements PartStore {

	private static Logger log = LoggerFactory.getLogger(ZipPartStore.class);


	HashMap<String, ByteArray> partByteArrays;

	public ZipPartStore() {
	}

	public ZipPartStore(File f) throws Docx4JException {
		log.info("Filepath = " + f.getPath() );

		ZipFile zf = null;
		try {
			if (!f.exists()) {
				log.info( "Couldn't find " + f.getPath() );
			}
			zf = new ZipFile(f);
		} catch (IOException ioe) {
			ioe.printStackTrace() ;
			throw new Docx4JException("Couldn't get ZipFile", ioe);
		}

		partByteArrays = new HashMap<String, ByteArray>();
		Enumeration entries = zf.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = (ZipEntry) entries.nextElement();
			//log.info( "\n\n" + entry.getName() + "\n" );
			InputStream in = null;
			try {
				byte[] bytes =  getBytesFromInputStream( zf.getInputStream(entry) );
				partByteArrays.put(entry.getName(), new ByteArray(bytes) );
			} catch (Exception e) {
				e.printStackTrace() ;
			}
		}
		 // At this point, we've finished with the zip file
		 try {
			 zf.close();
		 } catch (IOException exc) {
			 exc.printStackTrace();
		 }
	}

	public ZipPartStore(InputStream is) throws Docx4JException {

		partByteArrays = new HashMap<String, ByteArray>();
       try {
            ZipInputStream zis = new ZipInputStream(is);
            ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) {
				byte[] bytes =  getBytesFromInputStream( zis );
				//log.debug("Extracting " + entry.getName());
				partByteArrays.put(entry.getName(), new ByteArray(bytes) );
            }
            zis.close();
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new Docx4JException("Error processing zip file (is it a zip file?)", e);
        }

	}

	private PartStore sourcePartStore;

	/**
	 * Set this if its different to the target part store
	 * (ie this object)
	 */
	public void setSourcePartStore(PartStore partStore) {
		this.sourcePartStore = partStore;
	}

	/////// Load methods

	public boolean partExists(String partName) {
		return (partByteArrays.get(partName) !=null );
	}

	private byte[] getBytesFromInputStream(InputStream is)
			throws Exception {

			BufferedInputStream bufIn = new BufferedInputStream(is);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			BufferedOutputStream bos = new BufferedOutputStream(baos);
			int c = bufIn.read();
			while (c != -1) {
				bos.write(c);
				c = bufIn.read();
			}
			bos.flush();
			baos.flush();
			//bufIn.close(); //don't do that, since it closes the ZipInputStream after we've read an entry!
			bos.close();
			return baos.toByteArray();
		}

//	private static InputStream getInputStreamFromZippedPart(HashMap<String, ByteArray> partByteArrays,
//			String partName) throws IOException {
//
//        ByteArray bytes = partByteArrays.get(partName);
//        if (bytes == null) throw new IOException("part '" + partName + "' not found");
//		return bytes.getInputStream();
//	}

//	protected InputStream getInputStreamFromZippedPart(String partName) throws IOException {
	public InputStream loadPart(String partName) throws Docx4JException {

        ByteArray bytes = partByteArrays.get(partName);
        if (bytes == null) {
        	if (partName.endsWith(".rels")) {
        		log.debug("part '" + partName + "' not present in part store");
        	} else {
        		log.warn("part '" + partName + "' not present in part store");        		
        	}
        	return null;
        	//throw new Docx4JException("part '" + partName + "' not found");
        }
		return bytes.getInputStream();
	}
	
	@Override
	public long getPartSize(String partName) throws Docx4JException {
		
        ByteArray bytes = partByteArrays.get(partName);
        if (bytes == null) {
        	return -1;
        }
		return bytes.getLength();
	}
	
	
	///// Save methods

	private ZipOutputStream zos;

	/**
	 * @param zipOutputStream the zipOutputStream to set
	 */
	public void setOutputStream(OutputStream os) {
		this.zos = new ZipOutputStream(os);
	}

	public void saveContentTypes(ContentTypeManager ctm) throws Docx4JException {

		try {

	        zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
	        ctm.marshal(zos);
	        zos.closeEntry();

		} catch (Exception e) {
			throw new Docx4JException("Error marshalling Content_Types ", e);
		}

	}

	public void saveJaxbXmlPart(JaxbXmlPart part) throws Docx4JException {

		String targetName;
		if (part.getPartName().getName().equals("_rels/.rels")) {
			targetName = part.getPartName().getName();
		} else {
			targetName = part.getPartName().getName().substring(1);
		}

		try {
	        // Add ZIP entry to output stream.
	        zos.putNextEntry(new ZipEntry(targetName));

	        if (part.isUnmarshalled() ) {
	        	log.debug("marshalling " + part.getPartName() );
	        	part.marshal( zos );
	        } else {

	        	if (this.sourcePartStore==null) {

	        		throw new Docx4JException("part store has changed, and sourcePartStore not set");
	        		
	        		/* If you are seeing this exception:
	    			
		    			For processing efficiency, docx4j doesn't actually fully load a part until it is needed.
		    			
		    			To load it, it needs to know where to find it (ie its SourcePartStore).
		    			
		    			This info is stored at the package level, so if you move a part to a new package, that info is lost.
		    			
		    			3 ways to work around this:
		    			
		    			1. wordprocessingMLPackage.setSourcePartStore(jaxbXmlPart.getPackage().getSourcePartStore());
		    			
		    				but not so good, since in the general case, you might want to use several source packages,
		    				and, in any case, you can't retrieve the part if you've changed its name
		    				
		    			2. unmarshall it, so it is fully loaded and docx4j doesn't try to get it from the source part store
		    		
		    					jaxbXmlPart.getContents();
		    			
		    			   this works fine, but it is a bit opaque since it relies on a side effect / underlying knowledge of docx4j 
		    			   
		    			3. best: create a new part, and set its contents (eg via deepcopy) 
	    			*/	        		

	        	} else if (this.sourcePartStore==this) {

		        	// Just use the ByteArray
		        	log.debug(part.getPartName() + " is clean" );
		            ByteArray bytes = partByteArrays.get(
		            		part.getPartName().getName().substring(1) );
		            if (bytes == null) throw new IOException("part '" + part.getPartName() + "' not found");
			        zos.write( bytes.getBytes() );

	        	} else {
	        		InputStream is = sourcePartStore.loadPart(part.getPartName().getName().substring(1));
	        		int read = 0;
	        		byte[] bytes = new byte[1024];

	        		while ((read = is.read(bytes)) != -1) {
	        			zos.write(bytes, 0, read);
	        		}
	        		is.close();
	        	}
	        }


	        // Complete the entry
	        zos.closeEntry();

		} catch (Exception e) {
			throw new Docx4JException("Error marshalling JaxbXmlPart " + part.getPartName(), e);
		}
	}

	public void saveCustomXmlDataStoragePart(CustomXmlDataStoragePart part) throws Docx4JException {

		String targetName = part.getPartName().getName().substring(1);

		try {

	        // Add ZIP entry to output stream.
	        zos.putNextEntry(new ZipEntry(targetName));

	        part.getData().writeDocument( zos );

	        // Complete the entry
	        zos.closeEntry();

		} catch (Exception e) {
			throw new Docx4JException("Error marshalling CustomXmlDataStoragePart " + part.getPartName(), e);
		}

	}

	public void saveXmlPart(XmlPart part) throws Docx4JException {

		String targetName = part.getPartName().getName().substring(1);

		try {

		    // Add ZIP entry to output stream.
		    zos.putNextEntry(new ZipEntry(targetName));

		   Document doc =  part.getDocument();

			/*
			 * With Crimson, this gives:
			 *
				Exception in thread "main" java.lang.AbstractMethodError: org.apache.crimson.tree.XmlDocument.getXmlStandalone()Z
					at com.sun.org.apache.xalan.internal.xsltc.trax.DOM2TO.setDocumentInfo(DOM2TO.java:373)
					at com.sun.org.apache.xalan.internal.xsltc.trax.DOM2TO.parse(DOM2TO.java:127)
					at com.sun.org.apache.xalan.internal.xsltc.trax.DOM2TO.parse(DOM2TO.java:94)
					at com.sun.org.apache.xalan.internal.xsltc.trax.TransformerImpl.transformIdentity(TransformerImpl.java:662)
					at com.sun.org.apache.xalan.internal.xsltc.trax.TransformerImpl.transform(TransformerImpl.java:708)
					at com.sun.org.apache.xalan.internal.xsltc.trax.TransformerImpl.transform(TransformerImpl.java:313)
					at org.docx4j.model.datastorage.CustomXmlDataStorageImpl.writeDocument(CustomXmlDataStorageImpl.java:174)
			 *
			 */
			DOMSource source = new DOMSource(doc);
			 XmlUtils.getTransformerFactory().newTransformer().transform(source,
					 new StreamResult(zos) );


		    // Complete the entry
		    zos.closeEntry();

		} catch (Exception e) {
			throw new Docx4JException("Error marshalling XmlPart " + part.getPartName(), e);
		}
	}

	public void saveBinaryPart(Part part) throws Docx4JException {

		// Drop the leading '/'
		String resolvedPartUri = part.getPartName().getName().substring(1);

		try {
			
			byte[] bytes = null;
			
	        if (((BinaryPart)part).isLoaded() ) {

	            bytes = ((BinaryPart)part).getBytes();

	        } else {

	        	if (this.sourcePartStore==null) {

	        		throw new Docx4JException("part store has changed, and sourcePartStore not set");

	        	} else if (this.sourcePartStore==this) {

		        	// Just use the ByteArray
		        	log.debug(part.getPartName() + " is clean" );
		            ByteArray byteArray = partByteArrays.get(
		            		part.getPartName().getName().substring(1) );
		            if (byteArray == null) throw new IOException("part '" + part.getPartName() + "' not found");
		            bytes = byteArray.getBytes();

	        	} else {

	        		InputStream is = sourcePartStore.loadPart(part.getPartName().getName().substring(1));
	        		bytes = IOUtils.toByteArray(is);
	        	}
	        }
			
	        // Add ZIP entry to output stream.
			if (part instanceof OleObjectBinaryPart) {
				// Workaround: Powerpoint 2010 (32-bit) can't play eg WMV if it is compressed!
				// (though 64-bit version is fine)
				
				ZipEntry ze = new ZipEntry(resolvedPartUri);
				ze.setMethod(ZipOutputStream.STORED);
				
				// must set size, compressed size, and crc-32
				ze.setSize(bytes.length);
				ze.setCompressedSize(bytes.length);
				
			    CRC32 crc = new CRC32();
			    crc.update(bytes);	
			    ze.setCrc(crc.getValue());
				
				zos.putNextEntry(ze);				
			} else {
				zos.putNextEntry(new ZipEntry(resolvedPartUri));
			}

	        zos.write( bytes );

			// Complete the entry
	        zos.closeEntry();

		} catch (Exception e ) {
			throw new Docx4JException("Failed to put binary part", e);
		}

		log.debug( "success writing part: " + resolvedPartUri);

	}

	public void finishSave() throws Docx4JException {

		try {
			// Complete the ZIP file
			// Don't forget to do this or everything will appear
			// to work, but when you open the zip file you'll get an error
			// "End-of-central-directory signature not found."
	        zos.close();
		} catch (Exception e ) {
			throw new Docx4JException("Failed to put binary part", e);
		}

	}




	public static class ByteArray implements Serializable {

		private static final long serialVersionUID = -784146312250361899L;
		// 4469266984448028582L;

		private byte[] bytes;
		public byte[] getBytes() {
			return bytes;
		}

		private String mimetype;
		public String getMimetype() {
			return mimetype;
		}

		public ByteArray(byte[] bytes) {
			this.bytes = bytes;
			//log.info("Added " + bytes.length  );
		}


		public ByteArray(ByteBuffer bb, String mimetype ) {

			bb.rewind();
			bytes = new byte[bb.limit()];
			bb.get(bytes, 0, bytes.length);

			this.mimetype = mimetype;
		}


		public InputStream getInputStream() {
			return new ByteArrayInputStream(bytes);
		}

		public int getLength() {
			return bytes.length;
		}

	}




	@Override
	public void dispose() {
		// TODO Auto-generated method stub
		
	}

}
