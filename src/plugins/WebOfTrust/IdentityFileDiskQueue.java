/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.util.jobs.BackgroundJob;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
 * {@link IdentityFileQueue} implementation which writes the files to disk instead of keeping them
 * in memory.<br><br>
 * 
 * Deduplicating queue: Only the latest edition of each file is returned; see
 * {@link IdentityFileQueue} for details.<br>
 * The order of files is not preserved.<br>
 */
final class IdentityFileDiskQueue implements IdentityFileQueue {
	/** Subdirectory of WOT data directory where we put our data dirs. */
	private final File mDataDir;

	/** {@link #add(IdentityFileStream)} puts files to this subdir of {@link #mDataDir}. */
	private final File mQueueDir;

	/** {@link #poll()} puts files to this subdir of {@link #mDataDir}. */
	private final File mProcessingDir;

	/**
	 * When the stream of a file returned by {@link #poll()} is closed, the closing function of
	 * the stream will move the file to this subdir of {@link #mDataDir}. */
	private final File mFinishedDir;
	
	/**
	 * Amount of old files in {@link #mFinishedDir}, i.e. files from a previous session.<br>
	 * We use this to ensure that filename index prefixes of new files do not collide.<br><br>
	 * 
	 * Notice: We do intentionally track this separately instead of initializing
	 * {@link IdentityFileQueueStatistics#mFinishedFiles} with this value: The other statistics are
	 * not persisted, so they would not be coherent with this value. */
	private int mOldFinishedFileCount;

	/** @see #getStatistics() */
	private final IdentityFileQueueStatistics mStatistics = new IdentityFileQueueStatistics();
	
	/** @see #registerEventHandler(BackgroundJob) */
	private BackgroundJob mEventHandler;


	public IdentityFileDiskQueue(WebOfTrust wot) {
		mDataDir = new File(wot.getUserDataDirectory(), "IdentityFileQueue");
		mQueueDir = new File(mDataDir, "Queued");
		mProcessingDir = new File(mDataDir, "Processing");
		mFinishedDir = new File(mDataDir, "Finished");
		
		if(!mDataDir.exists() && !mDataDir.mkdir())
			throw new RuntimeException("Cannot create " + mDataDir);
		
		if(!mQueueDir.exists() && !mQueueDir.mkdir())
			throw new RuntimeException("Cannot create " + mQueueDir);
		
		if(!mProcessingDir.exists() && !mProcessingDir.mkdir())
			throw new RuntimeException("Cannot create " + mProcessingDir);
		
		if(!mFinishedDir.exists() && !mFinishedDir.mkdir())
			throw new RuntimeException("Cannot create " + mFinishedDir);

		cleanDirectories();
	}

	/** Used at startup to ensure that the data directories are in a clean state */
	private synchronized void cleanDirectories() {
		// Queue dir policy:
		// - Keep all queued files so we don't have to download them again.
		// - Count them so mStatistics.mQueuedFiles is correct.
		for(File file : mQueueDir.listFiles()) {
			if(!file.getName().endsWith(IdentityFile.FILE_EXTENSION)) {
				Logger.warning(this, "Unexpected file type: " + file.getAbsolutePath());
				continue;
			}

			++mStatistics.mQueuedFiles;
			++mStatistics.mTotalQueuedFiles;
		}


		// Processing dir policy:
		// In theory we could move the files back to the queue. But its possible that a colliding
		// filename exists there, which would need special code to handle.
		// Since there should only be 1 file at a time in processing, and lost files will
		// automatically be downloaded again, we just delete it to avoid the hassle of writing code
		// for moving it back.
		for(File file : mProcessingDir.listFiles()) {
			if(!file.getName().endsWith(IdentityFile.FILE_EXTENSION)) {
				Logger.warning(this, "Unexpected file type: " + file.getAbsolutePath());
				continue;
			}
			
			if(!file.delete())  {
				Logger.error(this, "Cannot delete old file in mProcessingDir: "
			                     + file.getAbsolutePath());
			}
		}
		
		
		// Finished dir policy:
		// The finished dir is an archival dir which archives old identity files for debug purposes.
		// Thus, we want to keep all files in mFinishedDir.
		// To ensure that new files do not collide with the index prefixes of old ones, we now need
		// to find the highest filename index prefix of the old files.
		int maxFinishedIndex = 0;

		for(File file: mFinishedDir.listFiles()) {
			String name = file.getName();
			
			if(!name.endsWith(IdentityFile.FILE_EXTENSION)) {
				Logger.warning(this, "Unexpected file type: " + file.getAbsolutePath());
				continue;
			}

			try {
				 int index = Integer.parseInt(name.substring(0, name.indexOf('_')));
				 maxFinishedIndex = Math.max(maxFinishedIndex, index);
			} catch(RuntimeException e) { // TODO: Code quality: Java 7
				                          // catch NumberFormatException | IndexOutOfBoundsException
				
				Logger.warning(this, "Cannot parse file name: " + file.getAbsolutePath());
				continue;
			}
		}
		
		mOldFinishedFileCount = maxFinishedIndex;
		
		
		// We cannot do this now since we have no event handler yet.
		// registerEventHandler() does it for us.
		/*
		if(mStatistics.mQueuedFiles != 0)
			mEventHandler.triggerExecution();
		*/
	}

	/**
	 * Wrapper class for storing an {@link IdentityFileStream} to disk via {@link Serializable}.
	 * This is used to write and read the actual files of the queue.
	 * 
	 * FIXME: Add checksum and validate it during deserialization. This is indicated because:
	 * 1) I have done a test run where I modified the XML on a serialized file - the result was that
	 *    the deserializer does not notice it, the modified XML was imported as is.
	 * 2) At startup, we do not delete pre-existing files. They might have been damaged due to
	 *    system crashes, force termination, etc. */
	private static final class IdentityFile implements Serializable {
		public static transient final String FILE_EXTENSION = ".wot-identity";
		
		private static final long serialVersionUID = 1L;

		/** @see IdentityFileStream#mURI */
		public final FreenetURI mURI;

		/** @see IdentityFileStream#mXMLInputStream */
		public final byte[] mXML;

		public IdentityFile(IdentityFileStream source) {
			mURI = source.mURI.clone();
			
			ByteArrayOutputStream bos = null;
			try {
				bos = new ByteArrayOutputStream(XMLTransformer.MAX_IDENTITY_XML_BYTE_SIZE + 1);
				FileUtil.copy(source.mXMLInputStream, bos, -1);
				mXML = bos.toByteArray();
			} catch(IOException e) {
				throw new RuntimeException(e);
			} finally {
				Closer.close(bos);
				Closer.close(source.mXMLInputStream);
			}
		}

		public void write(File file) {
			FileOutputStream fos = null;
			ObjectOutputStream ous = null;
			
			try {
				fos = new FileOutputStream(file);
				ous = new ObjectOutputStream(fos);
				ous.writeObject(this);
			} catch(IOException e) {
				throw new RuntimeException(e);
			} finally {
				Closer.close(ous);
				Closer.close(fos);
			}
		}

		public static IdentityFile read(File source) {
			FileInputStream fis = null;
			ObjectInputStream ois = null;
			
			try {
				fis = new FileInputStream(source);
				ois = new ObjectInputStream(fis);
				final IdentityFile deserialized = (IdentityFile)ois.readObject();
				assert(deserialized != null) : "Not an IdentityFile: " + source;
				return deserialized;
			} catch(IOException e) {
				throw new RuntimeException(e);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} finally {
				Closer.close(ois);
				Closer.close(fis);
			}
		}
	}

	@Override public synchronized void add(IdentityFileStream identityFileStream) {
		// We increment the counter before errors could occur so erroneously dropped files are
		// included: This ensures that the user might notice dropped files from the statistics in
		// the UI.
		++mStatistics.mTotalQueuedFiles;
		
		File filename = getQueueFilename(identityFileStream.mURI);
		// Delete for deduplication
		if(filename.exists()) {
			if(!filename.delete())
				throw new RuntimeException("Cannot write to " + filename);
			
			--mStatistics.mQueuedFiles;
			++mStatistics.mDeduplicatedFiles;
		}
		
		// FIXME: Measure how long this takes. The IdentityFetcher contains code which could be
		// recycled for that.
		new IdentityFile(identityFileStream).write(filename);
		
		++mStatistics.mQueuedFiles;
		
		assert(mStatistics.mQueuedFiles <= mStatistics.mTotalQueuedFiles);
		
		assert(mStatistics.mDeduplicatedFiles ==
			   mStatistics.mTotalQueuedFiles - mStatistics.mQueuedFiles
			   - mStatistics.mProcessingFiles - mStatistics.mFinishedFiles);
		
		if(mEventHandler != null)
			mEventHandler.triggerExecution();
		else
			Logger.error(this, "IdentityFile queued but no event handler is monitoring the queue!");
	}

	private File getQueueFilename(FreenetURI identityFileURI) {
		// We want to deduplicate editions of files for the same identity.
		// This can be done by causing filenames to always collide for the same identity:
		// An existing file of an old edition will be overwritten then.
		// We cause the collissions by using the ID of the identity as the only variable component
		// of the filename.
		return new File(mQueueDir,
			            getEncodedIdentityID(identityFileURI) + IdentityFile.FILE_EXTENSION);
	}

	private String getEncodedIdentityID(FreenetURI identityURI) {
		// FIXME: Encode the ID with base 36 to ensure maximal filesystem compatibility.
		return IdentityID.constructAndValidateFromURI(identityURI).toString();
	}

	@Override public synchronized IdentityFileStream poll() {
		File[] queue = mQueueDir.listFiles();
		assert(queue.length == mStatistics.mQueuedFiles);
		
		// In theory, we should not have to loop over the result of listFiles(), we could always
		// return the first slot in its resulting array: poll() is not required to return any
		// specific selection of files.
		// However, to be robust against things such as the user sticking arbitrary files in the
		// directory, we loop over the files in the queue dir nevertheless:
		// If processing a file fails, we try the others until we succeed. 
		for(File queuedFile : queue) {
			try {
				IdentityFile fileData = IdentityFile.read(queuedFile);
				
				// Before we can return the file data, we must move the on-disk file from mQueueDir
				// to mProcessingDir to prevent it from getting poll()ed again.
				File dequeuedFile = new File(mProcessingDir, queuedFile.getName());
				assert(!dequeuedFile.exists());
				if(!queuedFile.renameTo(dequeuedFile)) {
					throw new RuntimeException("Cannot move file, source: " + queuedFile
			                                 + "; dest: " + dequeuedFile);
				}
				
				// The InputStreamWithCleanup wrapper will remove the file from mProcessingDir once
				// the stream is close()d.
				IdentityFileStream result = new IdentityFileStream(fileData.mURI,
					new InputStreamWithCleanup(dequeuedFile, fileData,
						new ByteArrayInputStream(fileData.mXML)));
				
				++mStatistics.mProcessingFiles;
				assert(mStatistics.mProcessingFiles == 1);
				
				--mStatistics.mQueuedFiles;
				assert(mStatistics.mQueuedFiles >= 0);
				
				return result;
			} catch(RuntimeException e) {
				Logger.error(this, "Error in poll() for queued file: " + queuedFile, e);
				// Try whether we can process the next file
				continue;
			}
		}

		return null; // Queue is empty
	}

	/**
	 * When we return {@link IdentityFileStream} objects from {@link IdentityFileDiskQueue#poll()},
	 * we wrap their {@link InputStream} in this wrapper. Its purpose is to hook {@link #close()} to
	 * implement cleanup of our disk directories. */
	private final class InputStreamWithCleanup extends FilterInputStream {
		/**
		 * The backend file in {@link IdentityFileDiskQueue#mProcessingDir}.<br>
		 * On {@link #close()} we delete it; or archive it for debugging purposes.
		 * FIXME: Implement deletion. Currently only archival is implemented. */
		private final File mSourceFile;

		/**
		 * The URI where the File {@link #mSourceFile} was downloaded from.<br>
		 * If the file is to be archived for debugging purposes, the URI will be used for producing
		 * a new filename for archival. */
		private final FreenetURI mSourceURI;

		/** Used to prevent {@link #close()} from executing twice */
		private boolean mClosedAlready = false;


		public InputStreamWithCleanup(File fileName, IdentityFile fileData,
				InputStream fileStream) {
			super(fileStream);
			mSourceFile = fileName;
			mSourceURI = fileData.mURI;
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				synchronized(IdentityFileDiskQueue.this) {
					// Prevent wrong value of mProcessingFiles by multiple calls to close(), which
					// paranoid code might do.
					if(mClosedAlready)
						return;

					assert(mStatistics.mProcessingFiles == 1);
					
					File moveTo = getAndReserveFinishedFilename(mSourceURI);

					assert(mSourceFile.exists());
					assert(!moveTo.exists());

					if(!mSourceFile.renameTo(moveTo)) {
						Logger.error(this, "Cannot move file, source: " + mSourceFile
							             + "; dest: " + moveTo);
						
						// We must delete as fallback: Otherwise, subsequent processed files of the
						// same Identity would collide with the filenames in the mProcessingDir.
						if(!mSourceFile.delete())
							Logger.error(this, "Cannot delete file: " + mSourceFile);
						else
							--mStatistics.mProcessingFiles;
					} else
						--mStatistics.mProcessingFiles;
					
					assert(mStatistics.mProcessingFiles == 0);

					mClosedAlready = true;
				}
			}
		}
	}

	/**
	 * Returns a filename suitable for use in directory {@link #mFinishedDir}.<br>
	 * Subsequent calls will never return the same filename again.<br><br>
	 * 
	 * ATTENTION: Must be called while being synchronized(this).<br><br>
	 * 
	 * Format:<br>
	 *     "I_identityID-HASH_edition-E.wot-identity"<br>
	 * where:<br>
	 *     I = zero-padded integer counting up from 0, to tell the precise order in which queued
	 *         files were processed. The padding is for nice sorting in the file manager.<br>
	 *     HASH = the ID of the {@link Identity}.<br>
	 *     E = the {@link Identity#getEdition() edition} of the identity file, as a zero-padded long
	 *         integer.<br><br>
	 * 
	 * Notice: The filenames contain more information than WOT needs for general purposes of future
	 * external scripts. */
	private File getAndReserveFinishedFilename(FreenetURI sourceURI) {
		File result = new File(mFinishedDir,
			String.format("%09d_identityID-%s_edition-%018d" + IdentityFile.FILE_EXTENSION,
				++mStatistics.mFinishedFiles + mOldFinishedFileCount,
				getEncodedIdentityID(sourceURI),
				sourceURI.getEdition()));
		
		assert(mStatistics.mFinishedFiles <= mStatistics.mTotalQueuedFiles);
		
		return result;
	}

	@Override public synchronized void registerEventHandler(BackgroundJob handler) {
		if(mEventHandler != null) {
			throw new UnsupportedOperationException(
				"Support for more than one event handler is not implemented yet.");
		}
		
		mEventHandler = handler;
		
		// We preserve queued files across restarts, so as soon after startup as we know who
		// the event handler is, we must wake up the event handler to process the waiting files.
		if(mStatistics.mQueuedFiles != 0)
			mEventHandler.triggerExecution();
	}

	@Override public synchronized IdentityFileQueueStatistics getStatistics() {
		return mStatistics.clone();
	}
}
