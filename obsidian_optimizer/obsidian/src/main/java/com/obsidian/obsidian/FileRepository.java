package com.obsidian.obsidian;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Queue;
import java.util.Stack;
import org.springframework.stereotype.Component;

@Component
public class FileRepository {

    private final String ROOT_FILE = "C:\\Users\\ACER\\Desktop\\NewLife";
    ArrayList<String> cache;
    boolean cacheUpToDate = false;

    ArrayList<String> cacheReview;
    boolean cacheReviewUpToDate = false;

    public void invalidateCache(){
        cacheUpToDate = false;
    }

    public ArrayList<String> getNoteNames(){

        if(cache!=null&&cacheUpToDate){
            return cache;
        }

        ArrayList<String> names = new ArrayList<>();
        File root = new File(ROOT_FILE);

        // Use Stack to perform DFS
        Queue<File> st = new ArrayDeque<>();
        st.add(root);

        while (!st.isEmpty()) {
            File currentPath = st.poll();

            // If it's a file and ends with ".md", add to names
            if (currentPath.isFile() && currentPath.getName().endsWith(".md")) {
                names.add(currentPath.getAbsolutePath());
                continue;
            }

            // If it's a directory, add its contents to the stack
            if (currentPath.isDirectory()) {
                File[] files = currentPath.listFiles();
                if (files != null) {  // Avoid NPE if listFiles() returns null
                    for (File file : files) {
                        // Skip directories like .git and resources
                        if (file.isDirectory() && 
                            !file.getName().equals(".git") && 
                            !file.getName().equals("resources")) {
                            st.add(file);
                        }
                        // We only push files for directories, .md files are handled already
                        else if (file.isFile()) {
                            st.add(file);
                        }
                    }
                }
            }
        }
        cache = names;
        cacheUpToDate = true;

        Collections.sort(cache);

        return cache;
    }

    public ArrayList<String> getReviewNotes(){
        ArrayList<String> reviewNames = new ArrayList<>();
        BufferedReader reader = null;

        if(cacheReview!=null&&cacheReviewUpToDate){
            return cacheReview;
        }

        if(cache == null || cacheUpToDate == false){
            getNoteNames();
        }


        for(String pathString:cache){
            File file = new File(pathString);
            try {
                // Create a BufferedReader to read the file
                reader = new BufferedReader(new FileReader(file));
                String line;
                
                reader.readLine();
                line = reader.readLine();
                if(line!=null&&isBeforeToday(line)){
                    reviewNames.add(file.toString());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            if (reader != null) {
                reader.close();  // Close the reader to release resources
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        cacheReview = reviewNames;
        cacheReviewUpToDate = true;
    
        return cacheReview;
    }
    private boolean isBeforeToday(String line){
        boolean result = false;
        if(line.length()<18){
            return false;
        }
        String dateString = line.substring(8, 18); // Extract the date part
        LocalDate date;
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            date = LocalDate.parse(dateString, formatter);
        } catch (Exception e) {
            return false;
        }

        result = date.isBefore(LocalDate.now())||date.isEqual(LocalDate.now());
       

        return result;
    }

    public String getText(String path){
        String content = "";
        try {
            // Read the entire content of the file as a single String
            content = Files.readString(Paths.get(path));
            return content;
        } catch (IOException e) {
            System.out.println("Error reading the file: " + e.getMessage());
        }
        return content;
    }

    private String compress(String path, String prePath){
        int m = prePath.length();

        return path.substring(m+1);
    }
}
