package com.cpms.community.maintenance;

import com.cpms.community.AccountService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
public class MaintenancePhotos {
    private final Path root;
    public MaintenancePhotos(@Value("${maintenance.upload-dir:./uploads/maintenance}") String dir){root=Path.of(dir).toAbsolutePath().normalize();}
    public List<String> save(Long ticketId,List<MultipartFile> files){
        if(files.size()>3)throw AccountService.fail(HttpStatus.BAD_REQUEST,"Upload at most 3 photos.");
        List<byte[]> images=new ArrayList<>();
        for(var file:files){
            if(file.isEmpty()||file.getSize()>5*1024*1024)throw AccountService.fail(HttpStatus.BAD_REQUEST,"Each photo must be a JPG or PNG of at most 5 MB.");
            try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))){
                var readers=ImageIO.getImageReaders(input);
                if(!readers.hasNext())throw new IOException();
                ImageReader reader=readers.next();
                try{
                    String format=reader.getFormatName();
                    if(!format.equalsIgnoreCase("png")&&!format.equalsIgnoreCase("jpeg"))throw new IOException();
                    reader.setInput(input);
                    if((long)reader.getWidth(0)*reader.getHeight(0)>16000000)throw new IOException();
                    var decoded=reader.read(0);ByteArrayOutputStream output=new ByteArrayOutputStream();
                    ImageIO.write(decoded,"png",output);images.add(output.toByteArray());
                }finally{reader.dispose();}
            }catch(IOException|RuntimeException e){throw AccountService.fail(HttpStatus.BAD_REQUEST,"Use valid JPG or PNG photos, up to 16 megapixels each.");}
        }
        List<Path> written=new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)cleanup(written);}
        });
        List<String> urls=new ArrayList<>();
        try{
            Files.createDirectories(root);
            for(byte[] bytes:images){String key=UUID.randomUUID()+".png";Path path=root.resolve(key);written.add(path);Files.write(path,bytes,StandardOpenOption.CREATE_NEW);urls.add("/api/maintenance/"+ticketId+"/photos/"+key);}
        }catch(IOException e){cleanup(written);throw AccountService.fail(HttpStatus.INTERNAL_SERVER_ERROR,"Unable to save photos. Please try again.");}
        return urls;
    }
    private void cleanup(List<Path> paths){for(Path path:paths)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
    public byte[] read(String key){
        if(!key.matches("[a-f0-9-]{36}\\.png"))throw AccountService.fail(HttpStatus.NOT_FOUND,"Photo not found.");
        try{return Files.readAllBytes(root.resolve(key));}catch(IOException e){throw AccountService.fail(HttpStatus.NOT_FOUND,"Photo not found.");}
    }
}
