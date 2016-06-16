package edu.ucsb.nceas.mdqengine.model;

import java.util.List;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Recommendation {
	
	private String name;
	
	private List<Check> check;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<Check> getCheck() {
		return check;
	}

	public void setCheck(List<Check> check) {
		this.check = check;
	}

}
