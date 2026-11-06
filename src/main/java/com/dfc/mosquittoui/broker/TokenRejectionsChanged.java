package com.dfc.mosquittoui.broker;

/** The token login plugin published a new list of the tokens it did not accept (DynsecClient.TOKEN_REJECTED); fired asynchronously. */
public record TokenRejectionsChanged(String json) {
}
