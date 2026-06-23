package com.example.agentdemo.rag;

import com.example.agentdemo.rag.dto.RetrievedContext;

import java.util.List;

public interface DocumentRetriever {

    List<RetrievedContext> retrieve(String query, int limit);

}
