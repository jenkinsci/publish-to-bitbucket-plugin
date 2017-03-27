package org.jenkinsci.plugins.publishtobitbucket;

/**
 * Created by ali on 09/03/2017.
 */
public class BitbucketProject {
    private String key;
    private String name;
    private String description;

    public BitbucketProject(){

    }
    public BitbucketProject(String name, String key){
        this.name=name;
        this.key=key;
        this.description=name;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }


}
