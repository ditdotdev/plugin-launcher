package main

import (
	"github.com/ditdotdev/remote-sdk-go/remote"
)

type EchoRemote struct {
}

func (m EchoRemote) Type() (string, error) {
	return "echo", nil
}

func (m EchoRemote) FromURL(url string, additionalProperties map[string]string) (map[string]interface{}, error) {
	ret := map[string]interface{}{
		"url": url,
	}
	for k, v := range additionalProperties {
		ret[k] = v
	}
	return ret, nil
}

func (m EchoRemote) ToURL(properties map[string]interface{}) (string, map[string]string, error) {
	ret := map[string]string{}
	for k, v := range properties {
		ret[k] = v.(string)
	}
	return "echo://echo", ret, nil
}

func (m EchoRemote) GetParameters(remoteProperties map[string]interface{}) (map[string]interface{}, error) {
	return remoteProperties, nil
}

func (m EchoRemote) ValidateRemote(properties map[string]interface{}) error {
	return nil
}

func (m EchoRemote) ValidateParameters(parameters map[string]interface{}) error {
	return nil
}

func (m EchoRemote) ListCommits(properties map[string]interface{}, parameters map[string]interface{}, tags []remote.Tag) ([]remote.Commit, error) {
	res := []remote.Commit{{
		ID:         "one",
		Properties: map[string]interface{}{"tags": map[string]interface{}{"name": "one"}, "timestamp": "2019-09-20T13:45:36Z"},
	}, {
		ID:         "two",
		Properties: map[string]interface{}{"tags": map[string]interface{}{"name": "two"}, "timestamp": "2019-09-20T13:45:37Z"},
	}}
	n := 0
	for _, c := range res {
		if remote.MatchTags(c.Properties, tags) {
			res[n] = c
			n++
		}
	}
	res = res[:n]
	remote.SortCommits(res)

	return res, nil
}

func (m EchoRemote) GetCommit(properties map[string]interface{}, parameters map[string]interface{}, commitId string) (*remote.Commit, error) {
	if commitId == "echo" {
		return &remote.Commit{
			ID:         "echo",
			Properties: map[string]interface{}{"name": "echo", "timestamp": "2019-09-20T13:45:36Z"},
		}, nil
	} else {
		return nil, nil
	}
}

func main() {
	remote.Register(EchoRemote{})
	remote.Serve("echo")
}
