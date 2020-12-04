

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.IOResult;
import akka.stream.Materializer;
import akka.stream.alpakka.file.TarArchiveMetadata;
import akka.stream.alpakka.file.javadsl.Archive;
import akka.stream.alpakka.file.javadsl.Directory;
import akka.stream.javadsl.*;
import akka.util.ByteString;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletionStage;

public class FileProcessingUtil {

    private static final int MAX_GUNZIP_CHUNK_SIZE = 64000;
    private static final String TARGZ_EXT="tar.gz";


    public static void main(String[] args) throws Exception{

        String filenameNotOk="src/test/resources/container/kgoed_eakte_0000000018.tar";
        String filenameOk="src/test/resources/container/lbktag_eakte_0000000015.tar";

        //String filename=filenameOk;
        String filename=filenameNotOk;
        Path tempBase = Paths.get(System.getProperty("java.io.tmpdir"));
        File tmpDir = tempBase.resolve(FileProcessingUtil.class.getSimpleName()).toFile();
        Path outDir = tmpDir.toPath().resolve("out");
        if(tmpDir.exists() && !tmpDir.isDirectory()) {
            tmpDir.delete();
        }
        Path file=Paths.get(filename);
        ActorSystem system=ActorSystem.create("test");
        Materializer mat=Materializer.matFromSystem(system);
        FileProcessingUtil.process(file, outDir, mat)
        .thenAccept(done->{
            system.terminate();
        });
    }
    public static CompletionStage<Done> process(Path filename, Path targetDir, Materializer mat) {
        return FileIO.fromPath(filename)
                .via(unTarFlow(targetDir,mat))
                .log("process")
                .runWith(Sink.ignore(), mat);
    }

    private static Flow<ByteString, Done, NotUsed> unTarFlow(Path targetDir, Materializer mat){
        return Archive.tarReader()
                .map(pair->{
                    System.out.println("metadataPath:"+pair.first().filePath());
                    return pair;
                })
                .mapAsync(
                        1,
                        pair -> {
                            TarArchiveMetadata metadata = pair.first();

                            Source<ByteString, NotUsed> source = pair.second();

                            Path targetFile = targetDir.resolve(metadata.filePath());

                            if(metadata.isDirectory()){
                                return Source.single(targetFile)
                                             .filter(t-> (!t.toFile().exists() || !t.toFile().isDirectory()))
                                             //.via(Directory.mkdirs())
                                             .log("unTarFlow dir")
                                             .runWith(Sink.ignore(), mat)
                                             .thenCompose(d -> source.runWith(Sink.ignore(), mat));
                            }else{
                                if(targetFile.getFileName().toString().endsWith(TARGZ_EXT)){
                                    Path targetSubDir=targetFile.getParent().resolve(Paths.get(targetFile.getFileName().toString().substring(0,TARGZ_EXT.length()-2)+"/"));
                                    return source.via(Compression.gunzip(MAX_GUNZIP_CHUNK_SIZE))
                                                 .via(unTarFlow(targetSubDir,mat))
                                                 .log("unTarFlow uncompress")
                                                 .runWith(Sink.ignore(), mat);
                                }else
                                    /*return source.log("unTarFlow save file").runWith(FileIO.toPath(targetFile), mat)
                                                  .thenApply(res-> Done.getInstance());*/
                                    return source.runWith(Sink.ignore(),mat);
                            }

                        });

    }
}
