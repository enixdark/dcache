/*
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package org.dcache.chimera.nfs;

import com.google.common.base.Splitter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.dcache.chimera.nfs.ExportClient.IO;
import org.dcache.chimera.nfs.ExportClient.Root;


public class ExportFile {

    /**
     * The root node of pseudo fs.
     */
    private volatile PseudoFsNode _psudoFS = new PseudoFsNode(null);
    private final File _exportFile;

    public ExportFile(File file) throws IOException  {
        _exportFile = file;
        _psudoFS = scanExportFile(file);
    }

    public List<String> getExports() {
        List<String> out = new ArrayList<String>();
        PseudoFsNode pseudoFsRoot = _psudoFS;
        walk(out, pseudoFsRoot, null);
        return out;
    }

    private void walk(List<String> out, PseudoFsNode node, String path) {
        if(node.isMountPoint())
            out.add(path == null? "/": path);

        if(node.isLeaf())
            return;

        for(PseudoFsNode next: node.getChildren())
            walk(out, next, (path == null? "": path) + "/" + next.getData());
    }

    private PseudoFsNode scanExportFile(File exportFile) throws IOException {

        BufferedReader br = new BufferedReader(new FileReader(exportFile));
        PseudoFsNode pseudoFsRoot = new PseudoFsNode(null);

        String line;
        try {
            int lineCount = 0;
            while ((line = br.readLine()) != null) {

                ++lineCount;

                line = line.trim();
                if (line.length() == 0)
                    continue;

                if (line.charAt(0) == '#')
                    continue;

                FsExport  export;
                StringTokenizer st = new StringTokenizer(line);
                String path = st.nextToken();

                if( st.hasMoreTokens() ) {
                    List<ExportClient> clients = new ArrayList<ExportClient>();
                    while(st.hasMoreTokens() ) {

                        String hostAndOptions = st.nextToken();
                        StringTokenizer optionsTokenizer = new StringTokenizer(hostAndOptions, "(),");

                        String host = optionsTokenizer.nextToken();
                        Root isTrusted = ExportClient.Root.NOTTRUSTED;
                        IO rw = ExportClient.IO.RO;
                        while(optionsTokenizer.hasMoreTokens()) {

                            String option = optionsTokenizer.nextToken();
                            if( option.equals("rw") ) {
                                rw = ExportClient.IO.RW;
                                continue;
                            }

                            if( option.equals("no_root_squash") ) {
                                isTrusted = ExportClient.Root.TRUSTED;
                                continue;
                            }

                        }

                        ExportClient client = new ExportClient(host,isTrusted, rw );
                        clients.add(client);

                    }
                    export  = new FsExport(path, clients);
                }else{
                    ExportClient everyOne = new ExportClient("*",ExportClient.Root.NOTTRUSTED, ExportClient.IO.RO );

                    List<ExportClient> clients = new ArrayList<ExportClient>(1);
                    clients.add(everyOne);
                    export = new FsExport(path, clients );

                }

                pathToPseudoFs(pseudoFsRoot, path, export);
            }
        } finally {
            try {
                br.close();
            } catch (IOException dummy) {
                // ignored
            }
        }
        return pseudoFsRoot;
    }


    public FsExport getExport(String path) {

        Splitter splitter = Splitter.on('/').omitEmptyStrings();
        PseudoFsNode rootNode = _psudoFS;
        PseudoFsNode node = _psudoFS;

        for (String s : splitter.split(path)) {
            node = rootNode.getNode(s);
            if(node == null)
                return null;
            rootNode = node;
        }

        return node == null? null: node.getExport();
    }

    // FIXME: one trusted client has an access to all tree
    public  boolean isTrusted( java.net.InetAddress client ){


        List<String> exports = getExports();
        for( String path: exports ) {

            FsExport fsExport = getExport(path);
            if( fsExport.isTrusted(client) ) {
                return true;
            }

        }

        return false;

    }

    /**
     * Parse export path into a chain of nodes.
     * Each node represents a directory of the a pseudo file system.
     * @param path
     * @param e
     */
    private void pathToPseudoFs(PseudoFsNode root, String path, FsExport e) {

        Splitter splitter = Splitter.on('/').omitEmptyStrings();
        for (String s : splitter.split(path)) {
            PseudoFsNode node = root.getNode(s);
            if (node == null) {
                node = new PseudoFsNode(s);
                root.addChild(node);
            }
            root = node;
        }
        root.addExport(e);
    }

    public PseudoFsNode getPseuFsRoot() {
        return _psudoFS;
    }

    public void rescan() throws IOException {
        _psudoFS = scanExportFile(_exportFile);
    }
}
