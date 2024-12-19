package com.obsidian.obsidian;

import java.util.ArrayList;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;


@Controller
@CrossOrigin(origins = "*")
public class MyController {

    private final FileRepository repository;

    @Autowired
    MyController(FileRepository repository){
        this.repository = repository;
    }

    @GetMapping("names")
    @ResponseBody
    public ArrayList<String> getNames() {
        ArrayList<String> noteNames = new ArrayList<>();
        noteNames = repository.getNoteNames();
        return noteNames;
    }
    @GetMapping("review")
    @ResponseBody
    public ArrayList<String> getReviewName() {
        ArrayList<String> noteNames = new ArrayList<>();
        noteNames = repository.getReviewNotes();
        return noteNames;
    }

    @GetMapping("text")
    @ResponseBody
    public String getText(@RequestParam String noteName) {
        return repository.getText(noteName);
    }
    
}
