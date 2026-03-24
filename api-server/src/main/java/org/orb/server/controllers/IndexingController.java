package org.orb.server.controllers;

import org.orb.server.models.Repository;
import org.orb.server.services.IndexEngineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@RestController
@RequestMapping("/indexing")
public class IndexingController {

    @Autowired
    private IndexEngineService indexEngineService;

    @RequestMapping("/start")
    @ResponseBody
    public String startIndexing(@RequestBody Repository repository) throws IOException {
        Optional<Path>  repoPath = indexEngineService.startIndexing(repository.getName());
        return "Indexing process started for repository: " + repoPath;
    }
}
