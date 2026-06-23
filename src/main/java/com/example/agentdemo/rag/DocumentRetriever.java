package com.example.agentdemo.rag;

import com.example.agentdemo.rag.dto.RetrievedContext;

import java.util.List;

public interface DocumentRetriever {

    String name();

    List<RetrievedContext> retrieve(String query, int limit);

}
