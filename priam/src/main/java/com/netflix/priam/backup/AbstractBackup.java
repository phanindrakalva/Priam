package com.netflix.priam.backup;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.netflix.priam.IConfiguration;
import com.netflix.priam.backup.AbstractBackupPath.BackupFileType;
import com.netflix.priam.scheduler.Task;
import com.netflix.priam.utils.RetryableCallable;

/**
 * Abstract Backup class for uploading files to backup location
 */
public abstract class AbstractBackup extends Task
{
    private static final Logger logger = LoggerFactory.getLogger(AbstractBackup.class);
    protected final List<String> FILTER_KEYSPACE = Arrays.asList("OpsCenter");
    protected final List<String> FILTER_COLUMN_FAMILY = Arrays.asList("LocationInfo");
    protected final Provider<AbstractBackupPath> pathFactory;
    protected final IBackupFileSystem fs;

    @Inject
    public AbstractBackup(IConfiguration config, IBackupFileSystem fs, Provider<AbstractBackupPath> pathFactory)
    {
        super(config);
        this.fs = fs;
        this.pathFactory = pathFactory;
    }

    /**
     * Upload files in the specified dir. Does not delete the file in case of
     * error
     * 
     * @param parent
     *            Parent dir
     * @param type
     *            Type of file (META, SST, SNAP etc)
     * @return
     * @throws Exception
     */
    protected List<AbstractBackupPath> upload(File parent, final BackupFileType type) throws Exception
    {
        final List<AbstractBackupPath> bps = Lists.newArrayList();
        for (final File file : parent.listFiles())
        {
            try
            {
                AbstractBackupPath abp = new RetryableCallable<AbstractBackupPath>(3, RetryableCallable.DEFAULT_WAIT_TIME)
                {
                    public AbstractBackupPath retriableCall() throws Exception
                    {

                        final AbstractBackupPath bp = pathFactory.get();
                        bp.parseLocal(file, type);
                        String[] cfPrefix = bp.fileName.split("-");
                        if (cfPrefix.length > 1 && FILTER_COLUMN_FAMILY.contains(cfPrefix[0]))
                            return null;
                        upload(bp);
                        file.delete();
                        return bp;
                    }
                }.call();

                if(abp != null)
                    bps.add(abp);
            }
            catch(Exception e)
            {
                logger.error(String.format("Failed to upload local file %s. Ignoring to continue with rest of backup.", file), e);
            }
        }
        return bps;
    }

    /**
     * Upload specified file (RandomAccessFile) with retries
     */
    protected void upload(final AbstractBackupPath bp) throws Exception
    {
        new RetryableCallable<Void>()
        {
            @Override
            public Void retriableCall() throws Exception
            {
                fs.upload(bp, bp.localReader());
                return null;
            }
        }.call();
    }

    /**
     * Filters unwanted keyspaces and column families
     */
    public boolean isValidBackupDir(File keyspaceDir, File backupDir)
    {
        if (!backupDir.isDirectory() && !backupDir.exists())
            return false;
        String keyspaceName = keyspaceDir.getName();
        if (FILTER_KEYSPACE.contains(keyspaceName))
            return false;
        return true;
    }
}
