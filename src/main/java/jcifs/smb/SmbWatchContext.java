/**
 * © 2017 AgNO3 Gmbh & Co. KG
 * All right reserved.
 * 
 * Created: 15.03.2017 by mbechler
 */
package jcifs.smb;


import java.util.List;
import java.util.concurrent.Callable;

import org.apache.log4j.Logger;

import jcifs.SmbConstants;


/**
 * @author mbechler
 *
 */
public class SmbWatchContext implements AutoCloseable, Callable<List<FileNotifyInformation>> {

    private static final Logger log = Logger.getLogger(SmbWatchContext.class);

    private final SmbFileHandleImpl handle;
    private final int filter;
    private final boolean recursive;


    /**
     * @param fh
     * @param filter
     * @param recursive
     * 
     */
    public SmbWatchContext ( SmbFileHandleImpl fh, int filter, boolean recursive ) {
        this.handle = fh;
        this.filter = filter;
        this.recursive = recursive;
    }


    /**
     * Get the next set of changes
     * 
     * Will block until the server returns a set of changes that match the given filter. The file will be automatically
     * opened if it is not and should be closed with {@link #close()} when no longer
     * needed.
     * 
     * Closing the context should cancel a pending notify request, but that does not seem to work reliable in all
     * implementations.
     * 
     * Changes in between these calls (as long as the file is open) are buffered by the server, so iteratively calling
     * this method should provide all changes (size of that buffer can be adjusted through
     * {@link jcifs.Configuration#getNotifyBufferSize()}).
     * If the server cannot fulfill the request because the changes did not fit the buffer
     * it will return an empty list of changes.
     * 
     * @return changes since the last invocation
     * @throws SmbException
     */
    public List<FileNotifyInformation> watch () throws SmbException {
        if ( !this.handle.isValid() ) {
            throw new SmbException("Watch was broken by tree disconnect");
        }
        try ( SmbTreeHandleImpl th = this.handle.getTree() ) {

            if ( !th.hasCapability(SmbConstants.CAP_NT_SMBS) ) {
                throw new SmbUnsupportedOperationException("Not supported without CAP_NT_SMBS");
            }

            /*
             * NtTrans Notify Change Request / Response
             */
            NtTransNotifyChange request = new NtTransNotifyChange(th.getConfig(), this.handle.getFid(), this.filter, this.recursive);
            NtTransNotifyChangeResponse response = new NtTransNotifyChangeResponse(th.getConfig());

            if ( log.isTraceEnabled() ) {
                log.trace("Sending NtTransNotifyChange for " + this.handle);
            }
            th.send(request, response, false);
            if ( log.isTraceEnabled() ) {
                log.trace("Returned from NtTransNotifyChange " + response.status);
            }
            if ( response.status == 0x0000010B ) {
                this.handle.markClosed();
            }
            if ( response.status == 0x0000010C ) {
                response.notifyInformation.clear();
            }
            return response.notifyInformation;
        }
    }


    /**
     * {@inheritDoc}
     *
     * @see java.util.concurrent.Callable#call()
     */
    @Override
    public List<FileNotifyInformation> call () throws Exception {
        return watch();
    }


    /**
     * {@inheritDoc}
     *
     * @see java.lang.AutoCloseable#close()
     */
    @Override
    public void close () throws SmbException {
        if ( this.handle.isValid() ) {
            this.handle.close(0L);
        }
    }
}